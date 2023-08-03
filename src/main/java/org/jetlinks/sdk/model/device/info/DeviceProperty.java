package org.jetlinks.sdk.model.device.info;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import org.jetlinks.core.message.property.ReportPropertyMessage;
import org.jetlinks.core.metadata.types.GeoPoint;
import java.util.Date;


@Getter
@Setter
public class DeviceProperty {

    @Schema(description = "ID")
    private String id;

    @Schema(description = "设备ID")
    private String deviceId;

    @Schema(description = "属性ID")
    private String property;

    @Schema(description = "属性名")
    private String propertyName;

    @Schema(description = "类型")
    private String type;

    @Schema(description = "单位")
    private String unit;

    @Hidden
    private Object numberValue;

    @Hidden
    private Object objectValue;

    @Hidden
    private Date timeValue;

    @Hidden
    private String stringValue;

    @Hidden
    private GeoPoint geoValue;

    @Schema(description = "属性值")
    private Object value;

    @Schema(description = "格式化值")
    private Object formatValue;

    @Schema(description = "创建时间")
    private long createTime;

    @Schema(description = "数据时间")
    private long timestamp;

    @Schema(description = "格式化后的时间,在聚合查询时此字段有值")
    private String formatTime;

    /**
     * 设备状态值,如果是查询的数据库,此字段可能为{@link null}
     *
     * @see ReportPropertyMessage#getPropertyStates()
     */
    @Schema(description = "状态值")
    private String state;

}
