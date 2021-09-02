/*
 * Copyright 2014-2020 Groupon, Inc
 * Copyright 2020-2021 Equinix, Inc
 * Copyright 2014-2021 The Billing Project, LLC
 *
 * The Billing Project licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.killbill.billing.beatrix.integration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.joda.time.DateTime;
import org.joda.time.LocalDate;
import org.killbill.billing.ErrorCode;
import org.killbill.billing.ObjectType;
import org.killbill.billing.account.api.Account;
import org.killbill.billing.api.TestApiListener.NextEvent;
import org.killbill.billing.beatrix.util.InvoiceChecker.ExpectedInvoiceItemCheck;
import org.killbill.billing.catalog.api.BillingActionPolicy;
import org.killbill.billing.catalog.api.BillingPeriod;
import org.killbill.billing.catalog.api.PlanPhaseSpecifier;
import org.killbill.billing.catalog.api.ProductCategory;
import org.killbill.billing.entitlement.api.DefaultEntitlement;
import org.killbill.billing.entitlement.api.DefaultEntitlementSpecifier;
import org.killbill.billing.entitlement.api.Entitlement;
import org.killbill.billing.entitlement.api.Entitlement.EntitlementActionPolicy;
import org.killbill.billing.invoice.api.Invoice;
import org.killbill.billing.invoice.api.InvoiceApiException;
import org.killbill.billing.invoice.api.InvoiceItem;
import org.killbill.billing.invoice.api.InvoiceItemType;
import org.killbill.billing.invoice.api.InvoiceStatus;
import org.killbill.billing.invoice.model.CreditAdjInvoiceItem;
import org.killbill.billing.payment.api.Payment;
import org.killbill.billing.payment.api.PluginProperty;
import org.killbill.billing.subscription.api.user.DefaultSubscriptionBase;
import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.common.collect.ImmutableList;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

public class TestIntegrationVoidInvoice extends TestIntegrationBase {

    @Test(groups = "slow")
    public void testVoidInvoice() throws Exception {
        final int billingDay = 14;
        final DateTime initialCreationDate = new DateTime(2015, 5, 15, 0, 0, 0, 0, testTimeZone);
        // set clock to the initial start date
        clock.setTime(initialCreationDate);

        log.info("Beginning test with BCD of " + billingDay);
        final Account account = createAccountWithNonOsgiPaymentMethod(getAccountData(billingDay));

        add_AUTO_PAY_OFF_Tag(account.getId(), ObjectType.ACCOUNT);

        DefaultEntitlement baseEntitlement = createBaseEntitlementAndCheckForCompletion(account.getId(), "bundleKey", "Shotgun", ProductCategory.BASE, BillingPeriod.MONTHLY, NextEvent.CREATE, NextEvent.BLOCK, NextEvent.INVOICE);
        DefaultSubscriptionBase subscription = subscriptionDataFromSubscription(baseEntitlement.getSubscriptionBase());

        final List<ExpectedInvoiceItemCheck> expectedInvoices = new ArrayList<ExpectedInvoiceItemCheck>();
        expectedInvoices.add(new ExpectedInvoiceItemCheck(new LocalDate(2015, 6, 14), new LocalDate(2015, 7, 14), InvoiceItemType.RECURRING, new BigDecimal("249.95")));

        // Move through time and verify we get the same invoice
        busHandler.pushExpectedEvents(NextEvent.PHASE, NextEvent.INVOICE);
        clock.addDays(30);
        assertListenerStatus();

        List<Invoice> invoices = invoiceUserApi.getInvoicesByAccount(account.getId(), false, false, callContext);
        invoiceChecker.checkInvoice(invoices.get(1).getId(), callContext, expectedInvoices);

        // Void the invoice
        busHandler.pushExpectedEvents(NextEvent.INVOICE_ADJUSTMENT);
        invoiceUserApi.voidInvoice(invoices.get(1).getId(), callContext);
        assertListenerStatus();

        remove_AUTO_PAY_OFF_Tag(account.getId(), ObjectType.ACCOUNT);

        // Move through time
        busHandler.pushExpectedEvents(NextEvent.INVOICE, NextEvent.PAYMENT, NextEvent.INVOICE_PAYMENT);
        clock.addDays(31);
        assertListenerStatus();

        // get all invoices including the VOIDED; includeVoidedInvoices = true;
        invoices = invoiceUserApi.getInvoicesByAccount(account.getId(), false, true, callContext);
        assertEquals(invoices.size(), 3);
        // verify integrity of the voided
        invoiceChecker.checkInvoice(invoices.get(1).getId(), callContext, expectedInvoices);
        assertEquals(invoices.get(1).getStatus(), InvoiceStatus.VOID);
        // verify that the new invoice contains current and VOIDED charge
        expectedInvoices.add(new ExpectedInvoiceItemCheck(new LocalDate(2015, 7, 14), new LocalDate(2015, 8, 14), InvoiceItemType.RECURRING, new BigDecimal("249.95")));

        invoiceChecker.checkInvoice(invoices.get(2).getId(), callContext, expectedInvoices);

        // verify that the account balance is fully paid and a payment exists
        final BigDecimal accountBalance = invoiceUserApi.getAccountBalance(account.getId(), callContext);
        assertTrue(accountBalance.compareTo(BigDecimal.ZERO) == 0);

        final List<Payment> payments = paymentApi.getAccountPayments(account.getId(), false, false, ImmutableList.<PluginProperty>of(), callContext);
        assertEquals(payments.size(), 1);

        final Payment payment = payments.get(0);
        assertTrue(payment.getPurchasedAmount().compareTo(invoices.get(2).getChargedAmount()) == 0);

        // try to void an invoice that is already paid, it should fail.
        try {
            invoiceUserApi.voidInvoice(invoices.get(2).getId(), callContext);
            Assert.fail("Should fail to void invoice that is already paid");
        } catch (final InvoiceApiException e) {
            Assert.assertEquals(e.getCode(), ErrorCode.CAN_NOT_VOID_INVOICE_THAT_IS_PAID.getCode());
        }

        // Refund the payment
        busHandler.pushExpectedEvents(NextEvent.PAYMENT, NextEvent.INVOICE_PAYMENT);
        paymentApi.createRefundWithPaymentControl(account, payment.getId(), payment.getPurchasedAmount(), payment.getCurrency(), clock.getUTCNow(), null, PLUGIN_PROPERTIES, PAYMENT_OPTIONS, callContext);
        assertListenerStatus();

        busHandler.pushExpectedEvents(NextEvent.INVOICE_ADJUSTMENT);
        invoiceUserApi.voidInvoice(invoices.get(2).getId(), callContext);
        assertListenerStatus();
        invoices = invoiceUserApi.getInvoicesByAccount(account.getId(), false, true, callContext);
        assertEquals(invoices.size(), 3);
        assertEquals(invoices.get(1).getStatus(), InvoiceStatus.VOID);
        assertEquals(invoices.get(2).getStatus(), InvoiceStatus.VOID);
    }

    @Test(groups = "slow")
    public void testVoidRepairedInvoice() throws Exception {

        final DateTime initialDate = new DateTime(2013, 6, 15, 0, 0, 0, 0, testTimeZone);
        final LocalDate startDate = initialDate.toLocalDate();
        clock.setDeltaFromReality(initialDate.getMillis() - clock.getUTCNow().getMillis());

        final Account account = createAccountWithNonOsgiPaymentMethod(getAccountData(15));
        assertNotNull(account);

        add_AUTO_PAY_OFF_Tag(account.getId(), ObjectType.ACCOUNT);

        final PlanPhaseSpecifier spec = new PlanPhaseSpecifier("pistol-monthly-notrial");

        busHandler.pushExpectedEvents(NextEvent.INVOICE);
        final InvoiceItem inputCredit = new CreditAdjInvoiceItem(null, account.getId(), startDate, "credit invoice", new BigDecimal("20.00"), account.getCurrency(), null);
        invoiceUserApi.insertCredits(account.getId(), startDate, ImmutableList.of(inputCredit), true, null, callContext);
        assertListenerStatus();

        final BigDecimal accountBalance1 = invoiceUserApi.getAccountBalance(account.getId(), callContext);
        final BigDecimal accountCBA1 = invoiceUserApi.getAccountCBA(account.getId(), callContext);

        busHandler.pushExpectedEvents(NextEvent.BLOCK, NextEvent.CREATE, NextEvent.INVOICE);
        final UUID entitlementId = entitlementApi.createBaseEntitlement(account.getId(), new DefaultEntitlementSpecifier(spec, null, null, null), null, startDate, startDate, false, false, ImmutableList.<PluginProperty>of(), callContext);
        final Entitlement bpEntitlement = entitlementApi.getEntitlementForId(entitlementId, callContext);
        assertListenerStatus();

        final Invoice invoice2 = invoiceChecker.checkInvoice(account.getId(), 2, callContext,
                                                            new ExpectedInvoiceItemCheck(new LocalDate(2013, 6, 15), new LocalDate(2013, 7, 15), InvoiceItemType.RECURRING, new BigDecimal("19.95")),
                                                             new ExpectedInvoiceItemCheck(new LocalDate(2013, 6, 15), new LocalDate(2013, 6, 15), InvoiceItemType.CBA_ADJ, new BigDecimal("-19.95")));


        // 2013-07-01
        clock.addDays(16);
        busHandler.pushExpectedEvents(NextEvent.BLOCK, NextEvent.CANCEL, NextEvent.INVOICE);
        bpEntitlement.cancelEntitlementWithPolicyOverrideBillingPolicy(EntitlementActionPolicy.IMMEDIATE, BillingActionPolicy.IMMEDIATE, ImmutableList.<PluginProperty>of(), callContext);
        assertListenerStatus();

        final BigDecimal accountBalance2 = invoiceUserApi.getAccountBalance(account.getId(), callContext);
        final BigDecimal accountCBA2 = invoiceUserApi.getAccountCBA(account.getId(), callContext);

        busHandler.pushExpectedEvents(NextEvent.INVOICE_ADJUSTMENT);
        invoiceUserApi.voidInvoice(invoice2.getId(), callContext);
        assertListenerStatus();

        final BigDecimal accountBalance3 = invoiceUserApi.getAccountBalance(account.getId(), callContext);
        final BigDecimal accountCBA3 = invoiceUserApi.getAccountCBA(account.getId(), callContext);

        /* This lead to the following:
        2021-09-02T22:54:14.875+0000 [main] WARN org.killbill.billing.invoice.InvoiceDispatcher - Illegal invoicing state detected for accountId='6f1e5365-7a0e-40fe-bc11-203df1cb52e5', dryRunArguments='null', parking account
{cause=java.lang.IllegalStateException: Missing cancelledItem for cancelItem=REPAIR_ADJ{startDate='2013-07-01', endDate='2013-07-15', amount='-9.31', linkedItemId='0e920939-008d-49db-b8d1-4e91aec71f0f'}, code=4, formattedMsg='ILLEGAL INVOICING STATE'}
	at org.killbill.billing.invoice.generator.InvoicePruner.getFullyRepairedItemsClosure(InvoicePruner.java:95)
	at org.killbill.billing.invoice.generator.FixedAndRecurringInvoiceItemGenerator.generateItems(FixedAndRecurringInvoiceItemGenerator.java:97)
	at org.killbill.billing.invoice.generator.DefaultInvoiceGenerator.generateInvoice(DefaultInvoiceGenerator.java:101)
	at org.killbill.billing.invoice.InvoiceDispatcher.generateKillBillInvoice(InvoiceDispatcher.java:700)
	at org.killbill.billing.invoice.InvoiceDispatcher.processAccountWithLockAndInputTargetDate(InvoiceDispatcher.java:591)
         */
        invoiceUserApi.triggerInvoiceGeneration(account.getId(), clock.getUTCToday(), callContext);


        checkNoMoreInvoiceToGenerate(account.getId());

    }
}