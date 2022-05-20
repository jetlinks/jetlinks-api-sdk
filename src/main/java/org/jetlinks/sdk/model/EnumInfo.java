package org.jetlinks.sdk.model;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class EnumInfo<T> {
    private String text;

    private T value;
}
