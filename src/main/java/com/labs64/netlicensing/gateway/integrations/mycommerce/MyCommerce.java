package com.labs64.netlicensing.gateway.integrations.mycommerce;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.ws.rs.core.MultivaluedMap;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.labs64.netlicensing.domain.entity.License;
import com.labs64.netlicensing.domain.entity.LicenseTemplate;
import com.labs64.netlicensing.domain.entity.Licensee;
import com.labs64.netlicensing.domain.entity.Product;
import com.labs64.netlicensing.domain.entity.impl.LicenseImpl;
import com.labs64.netlicensing.domain.entity.impl.LicenseeImpl;
import com.labs64.netlicensing.domain.vo.Context;
import com.labs64.netlicensing.domain.vo.LicenseType;
import com.labs64.netlicensing.exception.NetLicensingException;
import com.labs64.netlicensing.gateway.bl.PersistingLogger;
import com.labs64.netlicensing.gateway.bl.TimeStampTracker;
import com.labs64.netlicensing.gateway.domain.entity.StoredLog;
import com.labs64.netlicensing.gateway.util.Constants;
import com.labs64.netlicensing.service.LicenseService;
import com.labs64.netlicensing.service.LicenseTemplateService;
import com.labs64.netlicensing.service.LicenseeService;
import com.labs64.netlicensing.service.ProductService;

@Component
public class MyCommerce {

    public static final class MyCommerceConstants {
        public static final String NEXT_CLEANUP_TAG = "MyCommerceNextCleanup";
    
        public static final int PERSIST_PURCHASE_DAYS = 3;
    
        public static final String ENDPOINT_BASE_PATH = "mycommerce";
        public static final String ENDPOINT_PATH_CODEGEN = "codegen";
        public static final String ENDPOINT_PATH_LOG = "log";
        public static final String SAVE_USER_DATA = "saveUserData";
        public static final String QUANTITY_TO_LICENSEE = "quantityToLicensee";
    
        /** myCommerce purchase id */
        public static final String PURCHASE_ID = "PURCHASE_ID";
        /** increments for each product for multiple products in one purchase */
        public static final String RUNNING_NO = "RUNNING_NO";
        public static final String PURCHASE_DATE = "PURCHASE_DATE";
        /** ID of the product purchased */
        public static final String PRODUCT_ID = "PRODUCT_ID";
        public static final String QUANTITY = "QUANTITY";
        /** The name to which the customer chose to license the product */
        public static final String REG_NAME = "REG_NAME";
        /** 1st Customizable Field */
        public static final String ADDITIONAL1 = "ADDITIONAL1";
        /** 2nd Customizable Field */
        public static final String ADDITIONAL2 = "ADDITIONAL2";
        /** The name of the reseller or affiliate involved in this order */
        public static final String RESELLER = "RESELLER";
    
        public static final String LASTNAME = "LASTNAME";
        public static final String FIRSTNAME = "FIRSTNAME";
        public static final String COMPANY = "COMPANY";
        public static final String EMAIL = "EMAIL";
        public static final String PHONE = "PHONE";
        public static final String FAX = "FAX";
        public static final String STREET = "STREET";
        public static final String CITY = "CITY";
        public static final String ZIP = "ZIP";
        public static final String STATE = "STATE";
        public static final String COUNTRY = "COUNTRY";
        /**
         * (not present) = ISO-8859-1 (Latin 1) encoding<br>
         * "UTF8" = UTF8 Unicode
         */
        public static final String ENCODING = "ENCODING";
        public static final String LANGUAGE_ID = "LANGUAGE_ID";
        /** Name of the promotion */
        public static final String PROMOTION_NAME = "PROMOTION_NAME";
        /** The actual promotion coupon code used for this order */
        public static final String PROMOTION_COUPON_CODE = "PROMOTION_COUPON_CODE";
        /** Date of Subscription */
        public static final String SUBSCRIPTION_DATE = "SUBSCRIPTION_DATE";
        /** Start date of current re-billing period */
        public static final String START_DATE = "START_DATE";
        /** Expiry date (date of next re-billing) */
        public static final String EXPIRY_DATE = "EXPIRY_DATE";
        /**
         * The customer’s two letter country code, For example: US=United States, DE=GERMANY
         */
        public static final String ISO_CODE = "ISO_CODE";
        /**
         * Customer has agreed to receive the publisher's newsletter or not. Possible values: NLALLOW="YES" or "NO"
         */
        public static final String NLALLOW = "NLALLOW";
        /**
         * Payment on invoice for purchase orders. Possible values: INVOICE="UNPAID" or "PAID"
         */
        public static final String INVOICE = "INVOICE";
        /** custom field from myCommerce (licenseeNumber) */
        public static final String LICENSEE_NUMBER = "ADD[LICENSEENUMBER]";
    
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(MyCommerce.class);

    @Inject
    private PersistingLogger persistingLogger;

    @Inject
    private TimeStampTracker timeStampTracker;

    @Inject
    private MyCommercePurchaseRepository myCommercePurchaseRepository;

    public String codeGenerator(final Context context, final String purchaseId, final String productNumber,
            final List<String> licenseTemplateList, final boolean quantityToLicensee, final boolean isSaveUserData,
            final MultivaluedMap<String, String> formParams) throws NetLicensingException {

        final String logMessage = "Executing MyCommerce Code Generator for productNumber: " + productNumber
                + ", licenseTemplateList: " + licenseTemplateList.toString() + ", formParams: " + formParams.toString();
        persistingLogger.log(productNumber, purchaseId, StoredLog.Severity.INFO, logMessage, LOGGER);

        final List<String> licensees = new ArrayList<>();
        if (formParams.isEmpty() || licenseTemplateList.isEmpty()) {
            throw new MyCommerceException("Required parameters not provided");
        }
        final String licenseeNumber = formParams.getFirst(MyCommerce.MyCommerceConstants.LICENSEE_NUMBER);
        if (quantityToLicensee && licenseeNumber != null && !licenseeNumber.isEmpty()) {
            throw new MyCommerceException(
                    "'" + MyCommerce.MyCommerceConstants.LICENSEE_NUMBER + "' is not allowed in '"
                            + MyCommerce.MyCommerceConstants.QUANTITY_TO_LICENSEE + "' mode");
        }
        final String quantity = formParams.getFirst(MyCommerce.MyCommerceConstants.QUANTITY);
        if (quantity == null || quantity.isEmpty() || Integer.parseInt(quantity) < 1) {
            throw new MyCommerceException("'" + MyCommerce.MyCommerceConstants.QUANTITY + "' invalid or not provided");
        }

        final Product product = ProductService.get(context, productNumber);
        final Map<String, LicenseTemplate> licenseTemplates = getLicenseTemplates(context, licenseTemplateList);
        Licensee licensee = new LicenseeImpl();
        boolean isNeedCreateNewLicensee = true;

        // try to get existing Licensee
        if (!quantityToLicensee) {
            licensee = getExistingLicensee(context, licenseeNumber, purchaseId, productNumber);
            // if license template and licensee are bound to different products, need to create new licensee
            isNeedCreateNewLicensee = isNeedCreateNewLicensee(licensee, productNumber);
        }

        // create licenses
        for (int i = 1; i <= Integer.parseInt(quantity); i++) {
            // create new Licensee, if not existing or multipleLicenseeMode
            if (licensee == null || isNeedCreateNewLicensee || quantityToLicensee) {
                isNeedCreateNewLicensee = false;
                licensee = new LicenseeImpl();
                if (isSaveUserData) {
                    addCustomPropertiesToLicensee(formParams, licensee);
                }
                licensee.setActive(true);
                licensee.setProduct(product);
                licensee.addProperty(Constants.NetLicensing.PROP_MARKED_FOR_TRANSFER, "true");
                licensee = LicenseeService.create(context, productNumber, licensee);
            }
            for (final LicenseTemplate licenseTemplate : licenseTemplates.values()) {
                final License newLicense = new LicenseImpl();
                newLicense.setActive(true);
                // Required for timeVolume.
                if (LicenseType.TIMEVOLUME.equals(licenseTemplate.getLicenseType())) {
                    newLicense.addProperty(Constants.NetLicensing.PROP_START_DATE, "now");
                }
                LicenseService.create(context, licensee.getNumber(), licenseTemplate.getNumber(), null, newLicense);
            }
            if (!licensees.contains(licensee.getNumber())) {
                licensees.add(licensee.getNumber());
            }
        }
        if (!quantityToLicensee) {
            persistPurchaseLicenseeMapping(licensee.getNumber(), purchaseId, productNumber);
            removeExpiredPurchaseLicenseeMappings();
        }
        return StringUtils.join(licensees, "\n");
    }

    public String getErrorLog(final Context context, final String productNumber, final String purchaseId)
            throws NetLicensingException {
        ProductService.get(context, productNumber);// dummy request

        List<StoredLog> logs = new ArrayList<>();

        if (purchaseId != null && !purchaseId.isEmpty()) {
            logs = persistingLogger.getLogsByKeyAndSecondaryKey(productNumber, purchaseId);
        } else {
            logs = persistingLogger.getLogsByKey(productNumber);
        }
        final StringBuilder logStringBuilder = new StringBuilder();
        if (logs.isEmpty()) {
            logStringBuilder.append("No log entires for ");
            logStringBuilder.append(Constants.NetLicensing.PRODUCT_NUMBER);
            logStringBuilder.append("=");
            logStringBuilder.append(productNumber);
            if (purchaseId != null && !purchaseId.isEmpty()) {
                logStringBuilder.append(" and ");
                logStringBuilder.append(MyCommerce.MyCommerceConstants.PURCHASE_ID);
                logStringBuilder.append("=");
                logStringBuilder.append(purchaseId);
            }
            logStringBuilder.append(" within last ");
            logStringBuilder.append(Constants.LOG_PERSIST_DAYS);
            logStringBuilder.append(" days.");
        } else {
            for (final StoredLog log : logs) {
                logStringBuilder.append(log.getTimestamp());
                logStringBuilder.append(" ");
                logStringBuilder.append(log.getSeverity());
                logStringBuilder.append(" ");
                logStringBuilder.append(log.getMessage());
                logStringBuilder.append("\n");
            }
        }
        return logStringBuilder.toString();
    }

    private boolean isNeedCreateNewLicensee(final Licensee licensee, final String productNumber) {
        boolean isNeedCreateNewLicensee = false;
        if (licensee != null) {
            if (!licensee.getProduct().getNumber().equals(productNumber)) {
                isNeedCreateNewLicensee = true;
            }
        } else {
            isNeedCreateNewLicensee = true;
        }
        return isNeedCreateNewLicensee;
    }

    private void persistPurchaseLicenseeMapping(final String licenseeNumber, final String purchaseId,
            final String productNumber) {
        MyCommercePurchase myCommercePurchase = myCommercePurchaseRepository
                .findFirstByPurchaseIdAndProductNumber(purchaseId, productNumber);
        if (myCommercePurchase == null) {
            myCommercePurchase = new MyCommercePurchase();
            myCommercePurchase.setLicenseeNumber(licenseeNumber);
            myCommercePurchase.setPurchaseId(purchaseId);
            myCommercePurchase.setProductNumber(productNumber);
        }
        myCommercePurchase.setTimestamp(new Date());
        myCommercePurchaseRepository.save(myCommercePurchase);
    }

    private void removeExpiredPurchaseLicenseeMappings() {
        if (timeStampTracker.isTimeOutExpired(MyCommerce.MyCommerceConstants.NEXT_CLEANUP_TAG,
                Constants.CLEANUP_PERIOD_MINUTES)) {
            final Calendar earliestPersistTime = Calendar.getInstance();
            earliestPersistTime.add(Calendar.DATE, -MyCommerce.MyCommerceConstants.PERSIST_PURCHASE_DAYS);
            myCommercePurchaseRepository.deleteByTimestampBefore(earliestPersistTime.getTime());
        }
    }

    private void addCustomPropertiesToLicensee(final MultivaluedMap<String, String> formParams,
            final Licensee licensee) {
        for (final Map.Entry<String, List<String>> entry : formParams.entrySet()) {
            if (!LicenseeImpl.getReservedProps().contains(entry.getKey()) && !entry.getValue().get(0).equals("")) {
                licensee.addProperty(entry.getKey(), entry.getValue().get(0));
            }
        }
    }

    private Map<String, LicenseTemplate> getLicenseTemplates(final Context context,
            final List<String> licenseTemplateList) throws MyCommerceException, NetLicensingException {
        final Map<String, LicenseTemplate> licenseTemplates = new HashMap<>();
        final Iterator<String> licenseTemplateIterator = licenseTemplateList.iterator();
        while (licenseTemplateIterator.hasNext()) {
            final LicenseTemplate licenseTemplate = LicenseTemplateService.get(context, licenseTemplateIterator.next());
            licenseTemplates.put(licenseTemplate.getNumber(), licenseTemplate);
        }
        return licenseTemplates;
    }

    private Licensee getExistingLicensee(final Context context, String licenseeNumber, final String purchaseId,
            final String productNumber) throws MyCommerceException, NetLicensingException {
        Licensee licensee = null;
        if (StringUtils.isBlank(licenseeNumber)) { // ADD[LICENSEENUMBER] is not provided, get from database
            final MyCommercePurchase myCommercePurchase = myCommercePurchaseRepository
                    .findFirstByPurchaseIdAndProductNumber(purchaseId, productNumber);
            if (myCommercePurchase != null) {
                licenseeNumber = myCommercePurchase.getLicenseeNumber();
                LOGGER.info("licenseeNumber obtained from repository: " + licenseeNumber);
            }
        }
        if (StringUtils.isNotBlank(licenseeNumber)) {
            licensee = LicenseeService.get(context, licenseeNumber);
        }
        return licensee;
    }

}