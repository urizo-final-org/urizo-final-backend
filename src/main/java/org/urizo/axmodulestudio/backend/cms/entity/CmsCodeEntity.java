package org.urizo.axmodulestudio.backend.cms.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "cms_code", schema = "app")
public class CmsCodeEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "code_id")
    private Long id;
    @Column(name = "group_key", nullable = false, length = 40)
    private String groupKey;
    @Column(name = "code_value", nullable = false, length = 40)
    private String value;
    @Column(nullable = false, length = 100)
    private String label;
    @Column(name = "display_order", nullable = false)
    private int displayOrder;
    @Column(nullable = false)
    private boolean enabled;

    protected CmsCodeEntity() {}
    public CmsCodeEntity(String groupKey, String value, String label, int order, boolean enabled) {
        this.groupKey = groupKey;
        this.value = value;
        change(label, order, enabled);
    }
    public void change(String label, int order, boolean enabled) {
        this.label = label;
        this.displayOrder = order;
        this.enabled = enabled;
    }
    public Long getId() { return id; }
    public String getGroupKey() { return groupKey; }
    public String getValue() { return value; }
    public String getLabel() { return label; }
    public int getDisplayOrder() { return displayOrder; }
    public boolean isEnabled() { return enabled; }
}
