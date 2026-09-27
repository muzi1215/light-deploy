package com.lx.deploy.common.aop;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.lx.deploy.common.util.SecretMasker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 资源变更明细采集器：为 {@code @OperLog} 提供「这次操作把哪些字段从什么改成了什么」。
 *
 * <p>只靠脱敏后的请求参数回答不了「上一次这类事故是怎么发生的」——请求体里只有新值，
 * 看不到旧值。运维真正想问的是「环境是不是被从 vm-prod 改成了 lx-prod」，这需要变更前后的对照。</p>
 *
 * <p>实现上的关键取舍：<b>放在切面层的环绕通知里做，而不是让每个 Service 自己上报</b>。
 * 前置快照必须在业务方法执行前取，环绕通知天然有这个时机；若改成 Service 内部上报，
 * 每个 update 方法都要多两行代码，漏写一处就永久缺失该模块的变更记录。</p>
 *
 * <p>资源实体的读取统一走 MyBatis-Plus 的 {@link BaseMapper#selectById}：由 Spring 收集所有
 * Mapper bean 并按 bean 名建索引（{@code serverMapper} → {@code server}），
 * 新增模块无需改动本类，避免了「按类型注入一堆 Mapper」这种每加一个模块就得改一次的写法。</p>
 *
 * @author lx
 * @since 2026-09-27 01:15:00
 */
@Slf4j
@Component
public class ResourceDiffRecorder implements ApplicationContextAware {

    /** Mapper bean 名的固定后缀，去掉后即为资源类型键。 */
    private static final String MAPPER_SUFFIX = "Mapper";

    /** 变更明细中统一表示「空值」的文本；留空会让页面分不清「没值」与「没记到」。 */
    private static final String EMPTY = "-";

    /** 删除场景下统一表示新值的文本。 */
    private static final String DELETED = "(已删除)";

    /**
     * 不参与变更比对的字段。
     *
     * <p>基础字段要么不变（主键、软删除标记、创建时间），要么每次必变但毫无业务含义
     * （{@code update_time} 由数据库自身维护）——把它们列进明细只会淹没真正的变更。</p>
     *
     * <p>{@code ciphertext} 单独排除：它是 AES-GCM 密文，本身不构成明文泄漏，
     * 但每次换密钥或重加密都会整段变化，展示出来既没有可读性也会挤掉真正的变更。
     * 与「按敏感键脱敏」的区别是这里**不展示**而非替换成 {@code ***}。</p>
     */
    private static final Set<String> IGNORED_FIELDS =
            Set.of("id", "createTime", "updateTime", "isDeleted", "ciphertext");

    /**
     * 资源类型键到 Mapper 的索引。
     *
     * <p>首次使用时才构建（见 {@link #mappersByType()}）：放在构造期或 {@code ApplicationContextAware}
     * 回调里解析，会在容器尚未就绪时强制实例化全部 Mapper，属于不必要的提前开销。</p>
     */
    private volatile Map<String, BaseMapper<?>> index;

    /** 容器引用，用于按类型取 Mapper bean。 */
    private ApplicationContext applicationContext;

    /**
     * 取 Mapper 索引，首次调用时构建。
     *
     * <p>用 {@code getBeansOfType(BaseMapper.class)} 而不是构造器注入 {@code Map<String, BaseMapper<?>>}：
     * 前者按原始类型查找，不受泛型通配符匹配的影响，
     * 后者要依赖容器对 {@code BaseMapper<?>} 与 {@code ServerMapper extends BaseMapper<Server>} 的可赋值判定，
     * 万一判定失败就是启动崩溃——同一处风险已经踩过一次，这里选更稳的写法。</p>
     *
     * @return 不可变的资源类型到 Mapper 的索引；容器异常时为不可变空 Map
     */
    private Map<String, BaseMapper<?>> mappersByType() {
        if (index == null) {
            synchronized (this) {
                if (index == null) {
                    index = buildIndex();
                }
            }
        }
        return index;
    }

    /**
     * 扫描容器中的 Mapper bean 并建立索引。
     *
     * <p>key 是 bean 名去掉 {@code Mapper} 后缀的形式：{@code serverMapper} → {@code server}，
     * 与 {@code @OperLog(resourceType = "server")} 的取值对应。
     * 建完打一条 INFO，写错资源类型时可以从日志里直接看到「哪些是可用的」。</p>
     *
     * @return 索引；未收集到时为不可变空 Map
     */
    private Map<String, BaseMapper<?>> buildIndex() {
        Map<String, BaseMapper> beans = applicationContext.getBeansOfType(BaseMapper.class);
        Map<String, BaseMapper<?>> mapping = new LinkedHashMap<>();
        beans.forEach((beanName, mapper) -> {
            if (beanName != null && beanName.endsWith(MAPPER_SUFFIX) && mapper != null) {
                mapping.put(beanName.substring(0, beanName.length() - MAPPER_SUFFIX.length()), mapper);
            }
        });
        if (mapping.isEmpty()) {
            log.warn("未收集到任何 Mapper bean，字段级变更明细将全部为空；请确认 Mapper 扫描配置正常");
        } else {
            log.info("变更明细采集已就绪，可用资源类型：{}", mapping.keySet());
        }
        return Map.copyOf(mapping);
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * 读取资源变更前的快照。
     *
     * <p>必须在业务方法执行<b>之前</b>调用：方法一旦执行，数据库中的旧值就已经被覆盖，
     * 之后再查只会得到新值，比对结果必然是空。</p>
     *
     * @param resourceType 资源类型，取 {@code @OperLog} 的 resourceType
     * @param resourceId 资源主键，允许为空
     * @return JSON 树快照；资源类型未注册、主键为空或读取失败时返回 null
     */
    public JsonNode snapshot(String resourceType, String resourceId) {
        BaseMapper<?> mapper = mapperFor(resourceType);
        if (mapper == null || resourceId == null || resourceId.isBlank()) {
            return null;
        }
        try {
            Object entity = mapper.selectById(resourceId);
            JsonNode tree = SecretMasker.toTree(entity);
            if (tree == null && entity != null) {
                // 序列化失败在这里被吞成 null，属于「静默丢明细」，必须留一条日志（AGENTS.md §14.3）。
                log.warn("资源快照序列化失败，本次不记录变更明细，resourceType={}，resourceId={}",
                        resourceType, resourceId);
            }
            return tree;
        } catch (Exception ex) {
            // 快照是旁路上的旁路：取不到就降级为「本次没有变更明细」，绝不能影响操作本身。
            log.warn("读取变更前快照失败，resourceType={}，resourceId={}，原因：{}",
                    resourceType, resourceId, ex.getMessage());
            return null;
        }
    }

    /**
     * 比对变更前后的快照，产出变更明细 JSON。
     *
     * <p>三种情形的处理：</p>
     * <ol>
     *   <li>两侧都有值：逐字段比对 setState，只保留真正变化的字段；</li>
     *   <li>变更后查不到（多为删除，含 MyBatis-Plus 逻辑删除导致 {@code selectById} 落空）：
     *       把变更前的所有字段列为「原值 → (已删除)」，回答「删掉的是什么」；</li>
     *   <li>变更前查不到（新增后补快照的极端情形）：所有字段按「- → 新值」输出。</li>
     * </ol>
     *
     * <p>敏感字段（凭据、私钥、令牌等）按 {@link SecretMasker#isSensitiveKey} 直接跳过，
     * 与请求参数的脱敏共用同一套判定，避免出现「参数脱敏了、明细里却留下原文」。</p>
     *
     * @param before 变更前快照，允许为 null
     * @param after 变更后快照，允许为 null
     * @return 变更明细 JSON 数组文本；无变更或两侧都取不到时返回 null
     */
    public String captureDiff(JsonNode before, JsonNode after) {
        if (before == null && after == null) {
            return null;
        }
        List<Map<String, String>> changes = new ArrayList<>();
        if (after == null) {
            collectFields(before).forEach(entry ->
                    changes.add(changeItem(entry.getKey(), entry.getValue(), DELETED)));
        } else {
            JsonNode oldNode = before;
            collectFields(after).forEach(entry -> {
                String oldValue = oldNode == null ? EMPTY : textOf(oldNode.get(entry.getKey()));
                if (!oldValue.equals(entry.getValue())) {
                    changes.add(changeItem(entry.getKey(), oldValue, entry.getValue()));
                }
            });
        }
        return changes.isEmpty() ? null : SecretMasker.toJson(changes);
    }

    /**
     * 取资源类型对应的 Mapper。
     *
     * @param resourceType 资源类型，允许为空
     * @return Mapper 实例；未注册时返回 null
     */
    private BaseMapper<?> mapperFor(String resourceType) {
        if (resourceType == null || resourceType.isBlank()) {
            return null;
        }
        BaseMapper<?> mapper = mappersByType().get(resourceType);
        if (mapper == null) {
            log.warn("操作日志声明了未注册的资源类型：{}，本次不记录字段级变更", resourceType);
        }
        return mapper;
    }

    /**
     * 收集一个快照里需要比对的字段名与值。
     *
     * @param node 快照节点
     * @return 按原始字段顺序排列的字段名到展示值的映射
     */
    private List<Map.Entry<String, String>> collectFields(JsonNode node) {
        List<Map.Entry<String, String>> fields = new ArrayList<>();
        if (node == null || !node.isObject()) {
            return fields;
        }
        node.properties().forEach(entry -> {
            String field = entry.getKey();
            if (IGNORED_FIELDS.contains(field) || SecretMasker.isSensitiveKey(field)) {
                return;
            }
            fields.add(Map.entry(field, textOf(entry.getValue())));
        });
        return fields;
    }

    /**
     * 把节点转为展示文本。
     *
     * <p>容器节点（对象、数组）输出其 JSON 文本而不是 {@code asText()} 的空串——
     * 运行配置、健康检查配置这类字段是嵌套结构，空串会让页面看起来像「被清空了」。</p>
     *
     * @param node 节点，允许为 null
     * @return 展示文本；节点为空时返回 {@link #EMPTY}
     */
    private String textOf(JsonNode node) {
        if (node == null || node.isNull()) {
            return EMPTY;
        }
        if (node.isContainerNode()) {
            String json = SecretMasker.toJson(node);
            return json == null ? EMPTY : json;
        }
        String text = node.asText();
        return text.isEmpty() ? EMPTY : text;
    }

    /**
     * 构造一条变更记录。
     *
     * @param field 字段名
     * @param oldValue 变更前的展示值
     * @param newValue 变更后的展示值
     * @return 以字段名、旧值、新值为键的有序映射
     */
    private Map<String, String> changeItem(String field, String oldValue, String newValue) {
        Map<String, String> item = new LinkedHashMap<>();
        item.put("field", field);
        item.put("oldValue", oldValue);
        item.put("newValue", newValue);
        return item;
    }
}
