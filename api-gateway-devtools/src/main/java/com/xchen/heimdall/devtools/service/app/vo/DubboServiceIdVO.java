package com.xchen.heimdall.devtools.service.app.vo;

import lombok.*;

import javax.validation.constraints.NotNull;

/**
 * @author xchen
 * @date 2022/5/9
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class DubboServiceIdVO extends UserIdVO {

    @NotNull
    private Integer serviceId;
}
