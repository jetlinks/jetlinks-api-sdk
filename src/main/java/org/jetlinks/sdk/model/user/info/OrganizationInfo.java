package org.jetlinks.sdk.model.user.info;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;


@Getter
@Setter
public class OrganizationInfo {

    @Schema(description = "机构(部门ID)")
    private String id;

    @Schema(description = "名称")
    private String name;

    @Schema(description = "编码")
    private String code;

    @Schema(description = "上级ID")
    private String parentId;

    @Schema(description = "序号")
    private long sortIndex;

}
