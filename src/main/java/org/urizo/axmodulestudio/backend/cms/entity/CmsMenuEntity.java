package org.urizo.axmodulestudio.backend.cms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "cms_menu", schema = "app")
public class CmsMenuEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "menu_id", nullable = false, updatable = false)
    private Long menuId;

    @Column(name = "menu_name", nullable = false, length = 80)
    private String menuName;

    @Column(name = "path", nullable = false, length = 180)
    private String path;

    @Column(name = "parent_menu_id")
    private Long parentMenuId;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "target_type", nullable = false, length = 16)
    private String targetType;

    @Column(name = "target_id")
    private Long targetId;

    protected CmsMenuEntity() {
    }

    public CmsMenuEntity(
            String menuName,
            String path,
            Long parentMenuId,
            int displayOrder,
            String targetType,
            Long targetId) {
        change(menuName, path, parentMenuId, displayOrder, targetType, targetId);
    }

    public Long getMenuId() {
        return menuId;
    }

    public String getMenuName() {
        return menuName;
    }

    public String getPath() {
        return path;
    }

    public Long getParentMenuId() {
        return parentMenuId;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    public String getTargetType() {
        return targetType;
    }

    public Long getTargetId() {
        return targetId;
    }

    public void change(
            String menuName,
            String path,
            Long parentMenuId,
            int displayOrder,
            String targetType,
            Long targetId) {
        this.menuName = menuName;
        this.path = path;
        this.parentMenuId = parentMenuId;
        this.displayOrder = displayOrder;
        this.targetType = targetType;
        this.targetId = targetId;
    }

    public void mapTo(String targetType, Long targetId) {
        this.targetType = targetType;
        this.targetId = targetId;
    }
}
