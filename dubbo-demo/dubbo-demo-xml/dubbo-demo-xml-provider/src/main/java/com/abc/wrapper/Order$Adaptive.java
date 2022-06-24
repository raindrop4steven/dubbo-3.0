package com.abc.wrapper;

import org.apache.dubbo.common.extension.ExtensionLoader;

public class Order$Adaptive implements com.abc.wrapper.Order {
    public java.lang.String way() {
        throw new UnsupportedOperationException(
            "The method public abstract java.lang.String com.abc.wrapper.Order.way() of interface com.abc.wrapper"
                + ".Order is not adaptive method!");
    }

    public java.lang.String pay(org.apache.dubbo.common.URL arg0) {
        if (arg0 == null) {
            throw new IllegalArgumentException("url == null");
        }
        org.apache.dubbo.common.URL url = arg0;
        String extName = url.getParameter("order", "wechat");
        if (extName == null) {
            throw new IllegalStateException(
                "Failed to get extension (com.abc.wrapper.Order) name from url (" + url.toString()
                    + ") use keys([order])");
        }
        com.abc.wrapper.Order extension =
            (com.abc.wrapper.Order) ExtensionLoader.getExtensionLoader(com.abc.wrapper.Order.class)
                .getExtension(extName);
        return extension.pay(arg0);
    }
}
