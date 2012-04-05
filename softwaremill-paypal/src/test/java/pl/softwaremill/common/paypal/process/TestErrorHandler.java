package pl.softwaremill.common.paypal.process;

import pl.softwaremill.common.paypal.process.processors.PayPalProcessor;

/**
 * @Author: lukasz.zuchowski at gmail dot com
 * Date: 05.04.12
 * Time: 12:38
 */
public class TestErrorHandler extends PayPalErrorHandler {

    @Override
    public PayPalErrorHandler.ErrorMessage prepareErrorMessage() {
        return new ErrorMessage();
    }

    public static class ErrorMessage extends PayPalErrorHandler.ErrorMessage {

    }

    @Override
    public void processErrorMessage(PayPalErrorHandler.ErrorMessage errorMessage, PayPalProcessor processor) {

    }

    public static void main(String[] args) {
        System.out.println(new TestErrorHandler().prepareErrorMessage().getClass());
    }
}