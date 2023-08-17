package org.jetlinks.sdk.model.user.request;

import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.sdk.model.PagerResult;
import org.jetlinks.sdk.model.SimpleApiRequest;
import org.jetlinks.sdk.model.user.info.SaveUserRequest;
import org.jetlinks.sdk.model.user.info.UserDetail;
import org.springframework.core.ResolvableType;
import org.springframework.http.HttpMethod;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;


/**
 * 用户管理相关请求构造
 * @author wyj
 * @since 2.1
 */
public class UserManagementRequset {
    /**
     * 新增用户
     * @param saveUserRequest 用户详情
     * @return
     */
    public static SimpleApiRequest<UserDetail> addUser(SaveUserRequest saveUserRequest){
        return SimpleApiRequest
                .<UserDetail>builder()
                .post()
                .uri("/user/detail/_create")
                .body(saveUserRequest)
                .responseType(ResolvableType
                        .forClass(
                                String.class
                        ))
                .build();
    }

    /**
     * 分页获取用户详情
     * @param queryParamEntity 查询体
     * @return
     */
    public static SimpleApiRequest<UserDetail> getUser(QueryParamEntity queryParamEntity){
        return SimpleApiRequest
                .<UserDetail>builder()
                .post()
                .uri("/user/detail/_query")
                .body(queryParamEntity)
                .responseType(ResolvableType
                        .forClassWithGenerics(
                                PagerResult.class,
                                UserDetail.class
                        ))
                .build();
    }

    /**
     * 修改用户信息
     * @param userId 用户id
     * @param saveUserRequest 修改后的用户详情
     * @return
     */

    public static SimpleApiRequest<UserDetail> updateUser(String userId, SaveUserRequest saveUserRequest){
        URI uri = UriComponentsBuilder
                .fromUriString("/user/detail/{id}/_update")
                .build(userId);

        return SimpleApiRequest
                .<UserDetail>builder()
                .method(HttpMethod.PUT)
                .uri(uri.toString())
                .body(saveUserRequest)
                .responseType(ResolvableType
                        .forClass(
                                String.class
                        ))
                .build();
    }



}
