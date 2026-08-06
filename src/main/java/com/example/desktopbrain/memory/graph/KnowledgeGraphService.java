package com.example.desktopbrain.memory.graph;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 知识图谱服务：管理设备拓扑关系和用户偏好
 * 对应架构中的"记忆与人格层 - 知识图谱"
 */
@Service
public class KnowledgeGraphService {

    private final DeviceRepository deviceRepo;
    private final UserPreferenceRepository preferenceRepo;

    public KnowledgeGraphService(DeviceRepository deviceRepo,
                                  UserPreferenceRepository preferenceRepo) {
        this.deviceRepo = deviceRepo;
        this.preferenceRepo = preferenceRepo;
    }

    // ========== 设备管理 ==========

    /** 注册/更新设备 */
    public DeviceNode upsertDevice(String name, String type, String room) {
        DeviceNode existing = deviceRepo.findByName(name);
        if (existing != null) {
            existing.setRoom(room);
            existing.setLastSeen(System.currentTimeMillis());
            return deviceRepo.save(existing);
        }
        DeviceNode device = new DeviceNode(name, type, room);
        device.setLastSeen(System.currentTimeMillis());
        return deviceRepo.save(device);
    }

    /** 更新设备状态 */
    public void updateDeviceState(String name, String stateJson) {
        DeviceNode device = deviceRepo.findByName(name);
        if (device != null) {
            device.setState(stateJson);
            device.setLastSeen(System.currentTimeMillis());
            deviceRepo.save(device);
        }
    }

    /** 建立设备控制关系（A 控制 B） */
    public void addControlRelation(String controllerName, String targetName) {
        DeviceNode controller = deviceRepo.findByName(controllerName);
        DeviceNode target = deviceRepo.findByName(targetName);
        if (controller != null && target != null) {
            controller.getControls().add(target);
            deviceRepo.save(controller);
        }
    }

    /** 查询房间内所有设备 */
    public List<DeviceNode> getRoomDevices(String room) {
        return deviceRepo.findByRoom(room);
    }

    /** 按类型查询设备 */
    public List<DeviceNode> getDevicesByType(String type) {
        return deviceRepo.findByType(type);
    }

    /** 获取所有设备 */
    public List<DeviceNode> getAllDevices() {
        return deviceRepo.findAll();
    }

    // ========== 用户偏好 ==========

    /** 记录用户偏好 */
    public UserPreferenceNode savePreference(String category, String key, String value) {
        UserPreferenceNode pref = new UserPreferenceNode(category, key, value);
        return preferenceRepo.save(pref);
    }

    /** 学习用户偏好：如果已存在则增加置信度，否则新建 */
    public UserPreferenceNode learnPreference(String category, String key, String value) {
        List<UserPreferenceNode> existing = preferenceRepo.findByKey(key);
        for (UserPreferenceNode pref : existing) {
            if (pref.getCategory().equals(category) && pref.getValue().equals(value)) {
                pref.setConfidence(Math.min(1.0, pref.getConfidence() + 0.1));
                return preferenceRepo.save(pref);
            }
        }
        UserPreferenceNode pref = new UserPreferenceNode(category, key, value);
        pref.setConfidence(0.6);
        return preferenceRepo.save(pref);
    }

    /** 获取高置信度偏好 */
    public List<UserPreferenceNode> getActivePreferences(String category) {
        return preferenceRepo.findActivePreferences(category, 0.5);
    }

    /** 查询特定场景偏好 */
    public List<UserPreferenceNode> getScenePreferences(String condition) {
        return preferenceRepo.findByTriggerCondition(condition);
    }

    /** 获取所有偏好 */
    public List<UserPreferenceNode> getAllPreferences() {
        return preferenceRepo.findAll();
    }
}