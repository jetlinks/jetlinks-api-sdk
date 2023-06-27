
# JetLinks 物联网平台 API SDK


## 使用签名鉴权构造客户端:
```java

 ClientConfig clientConfig = new ClientConfig(
                "http://localhost:9000/jetlinks",
                "aSoq98aAxzP",
                "DaYsxpiWSfdTAPJyKW8rP2WAGyWErnsR"
        );

 ApiClient client = new WebApiClient(clientConfig);

```

## 使用OAuth2鉴权构造客户端:
```java

 OAuth2Config clientConfig = new OAuth2Config()
        .setServerBaseUrl("http://127.0.0.1:9000/api")
        .setAppId("1673272555247095808")
        .setSecureKey("c837ADCbSt8xszH5demTCfPCm388TiHJ")
        .setRedirectUri("http://127.0.0.1:8000")
        .validate();

 ApiClient client = new WebOAuth2ApiClient(clientConfig);

```

## 请求接口
例如：分页查询设备列表
```java

 ApiResponse<PagerResult<DeviceInfo>> response = client
        .request(QueryDeviceRequest
        .of(query -> query
        .where("productId", "demo-device")
        .doPaging(0,100)));

```