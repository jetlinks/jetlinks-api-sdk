
# JetLinks 物联网平台 API SDK


例:
```java

 ClientConfig clientConfig = new ClientConfig(
                "http://localhost:9000/jetlinks",
                // 
                "aSoq98aAxzP",
                "DaYsxpiWSfdTAPJyKW8rP2WAGyWErnsR"
        );

        ApiClient client = new WebApiClient(clientConfig);

        ApiResponse<PagerResult<DeviceInfo>> response = client
                .request(QueryDeviceRequest
                                 .of(query -> query
                                         .where("productId", "demo-device")
                                         .doPaging(0,100)));

```