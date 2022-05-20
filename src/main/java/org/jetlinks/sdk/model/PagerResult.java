package org.jetlinks.sdk.model;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class PagerResult<T> {

    //分页页码, 0为第一页
    private int pageIndex;

    //每页数量
    private int pageSize;

    //总数据量
    private int total;

    //数据列表
    private List<T> data;
}
