package com.example.desktopbrain.common;

/**
 * Home Assistant REST API 路径与字段名常量。
 */
public final class HaApiPaths {

    private HaApiPaths() {}

    // -- API 路径 --
    public static final String API_PREFIX = "/api/";
    public static final String STATES = "/api/states";
    public static final String SERVICES = "/api/services";
    public static final String CONFIG = "/api/config";

    // -- HTTP 头 --
    public static final String AUTHORIZATION = "Authorization";
    public static final String BEARER_PREFIX = "Bearer ";
    public static final String CONTENT_TYPE = "Content-Type";
    public static final String APPLICATION_JSON = "application/json";

    // -- 实体字段 --
    public static final String ENTITY_ID = "entity_id";
    public static final String FRIENDLY_NAME = "friendly_name";
    public static final String STATE = "state";
    public static final String ATTRIBUTES = "attributes";

    // -- 服务域/动作 --
    public static final String DOMAIN_LIGHT = "light";
    public static final String DOMAIN_CLIMATE = "climate";
    public static final String DOMAIN_SWITCH = "switch";
    public static final String SERVICE_TURN_ON = "turn_on";
    public static final String SERVICE_TURN_OFF = "turn_off";
    public static final String SERVICE_TOGGLE = "toggle";
    public static final String SERVICE_SET_TEMPERATURE = "set_temperature";

    // -- 属性字段 --
    public static final String BRIGHTNESS = "brightness";
    public static final String TEMPERATURE = "temperature";
    public static final String HUMIDITY = "humidity";
    public static final String POWER_CONSUMPTION = "power_consumption";
    public static final String BATTERY_LEVEL = "battery_level";
    public static final String UNIT_OF_MEASUREMENT = "unit_of_measurement";
}
