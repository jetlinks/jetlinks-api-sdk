package org.jetlinks.sdk.model.user.request;


import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.sdk.client.ApiClient;
import org.jetlinks.sdk.client.ClientConfig;
import org.jetlinks.sdk.client.WebApiClient;
import org.jetlinks.sdk.model.ApiResponse;
import org.jetlinks.sdk.model.user.info.RoleInfo;
import org.jetlinks.sdk.model.user.info.SaveUserRequest;
import org.jetlinks.sdk.model.user.info.UserDetail;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;


public class UserManagementRequsetTest {
    ClientConfig clientConfig = new ClientConfig(
            "http://192.168.33.46:8844",
            "w23y8dADCwPkRGnB",
            "dH7eNKTNmca33YstE88HdYRz8x7RZiFt"
    );
    ApiClient client = new WebApiClient(clientConfig);


    @Test
    public void addUserTest(){
        UserDetail userDetail = new UserDetail();
        List<RoleInfo> roleInfoList = new ArrayList<>();
        RoleInfo roleInfo = new RoleInfo();
        roleInfo.setId("demo_id");
        roleInfo.setName("demo_name");
        roleInfoList.add(roleInfo);
        userDetail.setRoleList(roleInfoList);
        userDetail.setName("demo_name");
        userDetail.setUsername("demo_username");
        userDetail.setPassword("demo_passWord");
        SaveUserRequest saveUserRequest = new SaveUserRequest();
        saveUserRequest.setUser(userDetail);
        ApiResponse<UserDetail> response = client
                .request(UserManagementRequset.addUser(saveUserRequest));
        System.out.println(JSON.toJSONString(response, SerializerFeature.PrettyFormat));
    }

    @Test
    public void queryUserTest(){
        QueryParamEntity queryParamEntity = new QueryParamEntity();
        queryParamEntity
                .toQuery()
                .where("username", "demo_name")
                .doPaging(0,100);
        ApiResponse<UserDetail> response = client
                .request(UserManagementRequset.getUser(queryParamEntity));
        System.out.println(JSON.toJSONString(response, SerializerFeature.PrettyFormat));
    }

    @Test
    public void updateUserTest(){
        UserDetail userDetail = new UserDetail();
        String userId = "demo_id";
        List<RoleInfo> roleInfoList = new ArrayList<>();
        RoleInfo roleInfo = new RoleInfo();
        roleInfo.setId("demo_roleId");
        roleInfo.setName("demo_roleName");
        roleInfoList.add(roleInfo);
        userDetail.setRoleList(roleInfoList);
        userDetail.setName("demo_name");
        userDetail.setUsername("demo_userName");
        userDetail.setPassword("demo_passWord");
        SaveUserRequest saveUserRequest = new SaveUserRequest();
        saveUserRequest.setUser(userDetail);
        ApiResponse<UserDetail> response = client
                .request(UserManagementRequset.updateUser(userId,saveUserRequest));
        System.out.println(JSON.toJSONString(response, SerializerFeature.PrettyFormat));
    }
}
