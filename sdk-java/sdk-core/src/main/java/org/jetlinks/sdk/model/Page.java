package org.jetlinks.sdk.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 平台分页结果；total 和分页参数以服务端归一化后的查询为准。
 *
 * @since 2.0
 */
public class Page<T> {
    public long total;
    public int pageIndex, pageSize;
    public List<T> data = new ArrayList<>();
}
