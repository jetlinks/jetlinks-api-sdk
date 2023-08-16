package org.jetlinks.sdk.model.device.info;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.GenericTreeSortSupportEntity;
import org.hswebframework.web.api.crud.entity.RecordCreationEntity;

import java.util.List;

@Getter
@Setter
public class DeviceCategory extends GenericTreeSortSupportEntity<String> implements RecordCreationEntity {


    @Schema(description = "id")
    private String id;

    @Schema(description = "标识")
    private String key;

    @Schema(description = "名称")
    private String name;

    @Schema(description = "说明")
    private String description;

    @Schema(description = "子节点")
    private List<DeviceCategory> children;

    @Schema(description = "物模型")
    private String metadata;

    @Schema(
        description = "创建者ID(只读)"
        , accessMode = Schema.AccessMode.READ_ONLY
    )
    private String creatorId;


    @Schema(
        description = "创建时间(只读)"
        , accessMode = Schema.AccessMode.READ_ONLY
    )
    private Long createTime;
}
