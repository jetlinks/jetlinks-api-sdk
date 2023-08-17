package org.jetlinks.sdk.model.user.info;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.validator.CreateGroup;
import org.hswebframework.web.validator.UpdateGroup;
import org.hswebframework.web.validator.ValidatorUtils;
import org.springframework.util.StringUtils;

import javax.validation.constraints.NotNull;
import java.util.Set;

/**
 * 创建用户请求，用于新建用户操作
 *
 * @author zhouhao
 * @since 2.0
 */
@Getter
@Setter
public class SaveUserRequest {

    @Schema(description = "用户基本信息")
    @NotNull
    private UserDetail user;

    @Schema(description = "角色ID列表")
    private Set<String> roleIdList;

    @Schema(description = "机构ID列表")
    private Set<String> orgIdList;

}
