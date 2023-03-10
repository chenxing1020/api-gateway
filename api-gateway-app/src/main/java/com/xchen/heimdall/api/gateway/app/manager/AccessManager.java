package com.xchen.heimdall.api.gateway.app.manager;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.ctrip.framework.apollo.Config;
import com.ctrip.framework.apollo.model.ConfigChange;
import com.ctrip.framework.apollo.model.ConfigChangeEvent;
import com.ctrip.framework.apollo.spring.annotation.ApolloConfig;
import com.ctrip.framework.apollo.spring.annotation.ApolloConfigChangeListener;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.xchen.heimdall.api.gateway.app.constant.VoField;
import com.xchen.heimdall.api.gateway.app.model.RequestModel;
import com.xchen.heimdall.api.gateway.app.util.HttpHeaderUtils;
import com.xchen.heimdall.common.api.ApolloGatewayApiDTO;
import com.xchen.heimdall.common.exception.errorcode.CustomException;
import com.xchen.heimdall.common.exception.errorcode.ForbiddenException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.List;
import java.util.Set;

import static com.xchen.heimdall.api.gateway.app.constant.HeaderKey.*;
import static com.xchen.heimdall.common.constant.ApolloNamespace.APP_ACCESSTOKEN;

/**
 * @author xchen
 * @date 2022/1/17
 */
@Component
@Slf4j
public class AccessManager {

    @ApolloConfig(APP_ACCESSTOKEN)
    private Config appAccessTokenConfig;

    @Resource
    private AuthManager authManager;

    @Resource
    private PermissionDataManager permissionDataManager;

    @Value("${host.list.eip:}")
    private Set<String> eipAddresses;
    @Value("${spring.cloud.sentinel.enabled}")
    private boolean enableSentinel;

    /**
     * appUserId -> accessToken
     */
    private final BiMap<String, String> appAccessTokenMap = HashBiMap.create();


    public void authorize(ServerHttpRequest servletRequest, RequestModel request, ApolloGatewayApiDTO gatewayApi) {
        // ????????????
        if (Boolean.FALSE.equals(gatewayApi.getLoginRequired())) {
            return ;
        }

        String userId = authenticate(servletRequest);
        authorizeByUserId(userId, request, gatewayApi);
    }

    /**
     * ????????????
     * ???????????????eip?????? -> ??????token -> app token
     * @param servletRequest
     * @return
     */
    public String authenticate(ServerHttpRequest servletRequest) {
        HttpHeaders headers = servletRequest.getHeaders();
        String accessToken = headers.getFirst(ACCESS_TOKEN);
        String eipUser = headers.getFirst(EIP_USER_ID);

        // ??????websocket????????????????????????????????????????????????accessToken
        String websocketToken = headers.getFirst(SEC_WEBSOCKT_PROTOCOL);
        if (StringUtils.isNotBlank(websocketToken)) {
            accessToken = websocketToken;
        }
        if (StringUtils.isNotBlank(eipUser) && HttpHeaderUtils.containClientIP(servletRequest, eipAddresses)) {
            // EIP?????????????????????????????????
            return eipUser;
        } else {
            // ??????
            return authManager.validateWithCache(accessToken);
        }
    }

    /**
     * ???????????????userId??????
     *
     * @param accessToken token
     * @param request     ??????vo
     * @param gatewayApi  api??????
     */
    public void authorizeByAccessToken(String accessToken, RequestModel request, ApolloGatewayApiDTO gatewayApi) {
        // ????????????
        if (Boolean.FALSE.equals(gatewayApi.getLoginRequired())) {
            return;
        }

        String userId = null;
        // ???????????????????????????
        if (appAccessTokenMap.containsValue(accessToken)) {
            userId = appAccessTokenMap.inverse().get(accessToken);
        } else {
            userId = authManager.validateWithCache(accessToken);
        }

        authorizeByUserId(userId, request, gatewayApi);
    }

    /**
     * ???????????????
     * 1?????????api??????????????????????????????
     * 2??????????????????hams client????????????
     *
     * @param userId     ?????????
     * @param request    ??????
     * @param gatewayApi gatewayApi
     */
    public void authorizeByUserId(String userId, RequestModel request, ApolloGatewayApiDTO gatewayApi) {
        userFlowControl(userId);

        List<String> permissionKeyList = gatewayApi.getPermissionKeyList();
        if (null != permissionKeyList &&
                !permissionKeyList.isEmpty() &&
                Boolean.FALSE.equals(permissionDataManager.userHasAnyPermission(userId,
                        gatewayApi.getPermissionKeyList()))) {
            throw new ForbiddenException("User has no auth related to api");
        }

        /*
         * ??????userId???????????????????????????
         * ???????????????vo??????json??????????????????????????????map??????
         * 1?????????????????????data????????????map?????????????????????
         * 2?????????????????????PagingWrapper????????????????????????data???
         */
        switch (gatewayApi.getVoWrapperType()) {
            case PAGING:
                VoDecorateManager.decorateWrapperVo(request, VoField.USER_ID_KEY, userId);
                break;
            case LIST:
                // TODO: ??????list?????????????????????
                break;
            case DEFAULT:
            default:
                VoDecorateManager.decorateVo(request, VoField.USER_ID_KEY, userId);
                break;
        }
    }

    @PostConstruct
    public void postConstruct() {
        appAccessTokenConfig.getPropertyNames().forEach(appUserId ->
                appAccessTokenMap.forcePut(appUserId, appAccessTokenConfig.getProperty(appUserId, ""))
        );
    }

    @ApolloConfigChangeListener(APP_ACCESSTOKEN)
    private void onChangeServiceToken(ConfigChangeEvent changeEvent) {
        changeEvent.changedKeys().forEach(appUserId -> {
            ConfigChange change = changeEvent.getChange(appUserId);
            String token = change.getNewValue();
            log.info(String.format("Found change - propertyName: %s, oldValue: %s, newValue: %s, changeType: %s",
                    change.getPropertyName(), change.getOldValue(), token, change.getChangeType()));

            switch (change.getChangeType()) {
                case ADDED:
                case MODIFIED:
                    appAccessTokenMap.forcePut(appUserId, token);
                    break;
                case DELETED:
                    appAccessTokenMap.remove(appUserId);
                    break;
                default:
                    break;
            }
        });
    }

    private void userFlowControl(String userId) {
        if (!enableSentinel) {
            return;
        }
        Entry entry = null;

        try {
            entry = SphU.entry(USER_ID, EntryType.IN, 1, userId);
        } catch (BlockException e) {
            throw new CustomException(1506, "userId " + userId + " ????????????");
        } finally {
            if (entry != null) {
                entry.exit(1, userId);
            }
        }
    }
}
