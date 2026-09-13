package org.urizo.axmodulestudio.backend.cms.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "cms_code_group", schema = "app")
public class CmsCodeGroupEntity {
    @Id @Column(name = "group_key", length = 40)
    private String key;
    @Column(nullable = false, length = 100)
    private String label;
    @Column(name = "display_order", nullable = false)
    private int displayOrder;
    @Column(nullable = false)
    private boolean enabled;

    protected CmsCodeGroupEntity() {}
    public CmsCodeGroupEntity(String key, String label, int order, boolean enabled) {
        this.key = key;
        change(label, order, enabled);
    }
    public void change(String label, int order, boolean enabled) {
        this.label = label;
        this.displayOrder = order;
        this.enabled = enabled;
    }
    public String getKey() { return key; }
    public String getLabel() { return label; }
    public int getDisplayOrder() { return displayOrder; }
    public boolean isEnabled() { return enabled; }
}
