package org.jetlinks.sdk.model;

import java.util.ArrayList;
import java.util.List;

/** Platform page result; total and page metadata describe the server's normalized query. @since 2.0 */
public class Page<T> {
    public long total;
    public int pageIndex, pageSize;
    public List<T> data = new ArrayList<>();
}
