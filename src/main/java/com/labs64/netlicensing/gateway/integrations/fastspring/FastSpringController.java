package com.labs64.netlicensing.gateway.integrations.fastspring;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.labs64.netlicensing.domain.vo.Context;
import com.labs64.netlicensing.domain.vo.SecurityMode;
import com.labs64.netlicensing.exception.NetLicensingException;
import com.labs64.netlicensing.gateway.bl.PersistingLogger;
import com.labs64.netlicensing.gateway.controller.restful.AbstractBaseController;
import com.labs64.netlicensing.gateway.domain.entity.StoredLog;
import com.labs64.netlicensing.gateway.integrations.mycommerce.MyCommerceException;
import com.labs64.netlicensing.gateway.util.Constants;
import com.labs64.netlicensing.gateway.util.security.SecurityHelper;

@Component
@Produces({ MediaType.TEXT_PLAIN })
@Path("/" + FastSpring.FastSpringConstants.ENDPOINT_BASE_PATH)
public class FastSpringController extends AbstractBaseController {

    @Autowired
    private FastSpring fastSpring;

    @Autowired
    private PersistingLogger persistingLogger;

    @POST
    @Path("/" + FastSpring.FastSpringConstants.ENDPOINT_PATH_CODEGEN)
    @Transactional
    public String codeGenerator(final MultivaluedMap<String, String> formParams) throws NetLicensingException {

        final Context context = getSecurityHelper().getContext();
        //context.setBaseUrl("http://localhost:28080/core/v2/rest"); // TODO(AY): TEMP

        final String apiKey = formParams.getFirst(FastSpring.FastSpringConstants.API_KEY);
        if (apiKey.isEmpty()) {
            throw new FastSpringException("'" + FastSpring.FastSpringConstants.API_KEY + "' parameter is required");
        }
        context.setSecurityMode(SecurityMode.APIKEY_IDENTIFICATION);
        context.setApiKey(apiKey);

        //auth
        if (!fastSpring.isPrivateKeyValid(context, formParams)) {
            throw new FastSpringException(FastSpring.FastSpringConstants.PRIVATE_KEY + "s are not matches");
        }

        if (!SecurityHelper.checkContextConnection(context)) {
            throw new FastSpringException("Wrong " + FastSpring.FastSpringConstants.API_KEY + " provided");
        }

        final String reference = formParams.getFirst(FastSpring.FastSpringConstants.REFERENCE);
        final String productNumber = formParams.getFirst(Constants.NetLicensing.PRODUCT_NUMBER);
        final String licenseTemplateNumber = formParams.getFirst(Constants.NetLicensing.LICENSE_TEMPLATE_NUMBER);

        if (StringUtils.isEmpty(productNumber) || StringUtils.isEmpty(licenseTemplateNumber)) {
            throw new FastSpringException("Required parameters not provided");
        }

        if (StringUtils.isEmpty(reference)) {
            final String message = "'" + FastSpring.FastSpringConstants.REFERENCE + "' is not provided";
            persistingLogger.log(productNumber, null, StoredLog.Severity.ERROR, message);
            throw new FastSpringException(message);
        }

        try {
            return fastSpring.codeGenerator(context, reference, formParams);
        } catch (final FastSpringException e) {
            persistingLogger.log(productNumber, reference, StoredLog.Severity.ERROR,
                    e.getResponse().getEntity().toString());
            throw e;
        } catch (final Exception e) {
            persistingLogger.log(productNumber, reference, StoredLog.Severity.ERROR, e.getMessage());
            throw new MyCommerceException(e.getMessage());
        }
    }

    @GET
    @Path("/" + FastSpring.FastSpringConstants.ENDPOINT_PATH_LOG + "/{" + Constants.NetLicensing.PRODUCT_NUMBER + "}")
    public String getErrorLog(@PathParam(Constants.NetLicensing.PRODUCT_NUMBER) final String productNumber) {
        try {
            final Context context = getSecurityHelper().getContext();
            return fastSpring.getErrorLog(context, productNumber);
        } catch (final Exception e) {
            throw new MyCommerceException(e.getMessage());
        }
    }

}
