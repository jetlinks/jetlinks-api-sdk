package org.jetlinks.sdk.model.user.info;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jetlinks.sdk.model.EnumInfo;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class UserDetail {

    @Schema(description = "用户ID")
    private String id;

    @Schema(description = "用户名")
    private String username;

    @Schema(hidden = true)
    private String password;

    @Schema(hidden = true)
    private EnumInfo<String> type;

    @Schema(description = "用户状态。1启用，0禁用")
    private Byte status;

    @Schema(description = "姓名")
    private String name;

    @Schema(description = "email")
    private String email;

    @Schema(description = "联系电话")
    private String telephone;

    @Schema(description = "头像图片地址")
    private String avatar;

    @Schema(description = "说明")
    private String description;

    @Schema(description = "创建时间")
    private long createTime;

    @Schema(description = "角色信息")
    private List<RoleInfo> roleList;

    @Schema(description = "所在机构(部门)信息")
    private List<OrganizationInfo> orgList;

    private boolean tenantDisabled;

}
