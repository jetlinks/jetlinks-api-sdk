package org.jetlinks.sdk.model.scene;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

/**
 * @author zyl
 * @date 3/8/2023
 */
@Getter
@Setter
public class ShakeLimit implements Serializable {
    @Schema(description = "是否开启防抖")
    private boolean enabled;

    //时间限制,单位时间内发生多次告警时,只算一次。单位:秒
    @Schema(description = "时间间隔(秒)")
    private int time;

    //触发阈值,单位时间内发生n次告警,只算一次。
    @Schema(description = "触发阈值(次)")
    private int threshold;

    //当发生第一次告警时就触发,为false时表示最后一次才触发(告警有延迟,但是可以统计出次数)
    @Schema(description = "是否第一次满足条件就触发")
    private boolean alarmFirst;
}
