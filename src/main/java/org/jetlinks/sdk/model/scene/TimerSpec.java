package org.jetlinks.sdk.model.scene;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.io.Serializable;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;
import java.util.Set;

/**
 * @author zyl
 * @date 3/8/2023
 */
@Getter
@Setter
public class TimerSpec implements Serializable {
    @Schema(description = "触发方式")
    @NotNull
    private Trigger trigger;

    //Cron表达式
    @Schema(description = "触发方式为[cron]时不能为空")
    private String cron;

    @Schema(description = "执行的时间.为空则表示每天,触发方式为[week]则为1-7,触发方式为[month]时则为1-31")
    private Set<Integer> when;

    @Schema(description = "执行模式,一次还是周期执行")
    private ExecuteMod mod;

    @Schema(description = "执行模式为[period]时不能为空")
    private Period period;

    @Schema(description = "执行模式为[once]时不能为空")
    private Once once;

    public enum ExecuteMod {
        period,
        once
    }

    @Getter
    @Setter
    public static class Period implements Serializable {
        private static final long serialVersionUID = 1L;
        //周期执行的时间区间
        @Schema(description = "执行时间范围从.格式:[hh:mm],或者[hh:mm:ss]")
        private String from;
        @Schema(description = "执行时间范围止.格式:[hh:mm],或者[hh:mm:ss]")
        private String to;

        @Schema(description = "周期值，如:每[every][unit]执行一次")
        private int every;

        @Schema(description = "周期执行单位")
        private PeriodUnit unit;

    }

    @AllArgsConstructor
    public enum PeriodUnit {
        seconds(ChronoUnit.SECONDS),
        minutes(ChronoUnit.MINUTES),
        hours(ChronoUnit.HOURS);
        private final TemporalUnit temporal;

    }

    @Getter
    @Setter
    @AllArgsConstructor(staticName = "of")
    @NoArgsConstructor
    public static class Once implements Serializable {
        private static final long serialVersionUID = 1L;
        //时间点
        @Schema(description = "时间点.格式:[hh:mm],或者[hh:mm:ss]")
        @NotBlank
        private String time;

    }

    public enum Trigger {
        week,
        month,
        cron
    }
}
