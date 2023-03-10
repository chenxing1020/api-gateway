package com.xchen.heimdall.devtools.service.app.dto;

import lombok.*;

@Data
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class GatewayApiDocDTO extends GatewayApiDTO {

    /**
     * ε₯ε
     */
    private PojoDTO inputParam;

    /**
     * εΊε
     */
    private PojoDTO returnParam;
}
