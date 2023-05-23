package net.sf.gilead.test.domain.misc;

import java.util.Collection;
import java.util.LinkedHashSet;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;

import org.hibernate.annotations.Cascade;

import net.sf.gilead.pojo.java5.LightEntity;

@Entity
public class Client extends LightEntity {

    private static final long serialVersionUID = -7663478891497366231L;

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private long id;

    @ManyToMany
    @JoinTable(name = "LinkClientToProject", joinColumns = @JoinColumn(name = "clientId"), inverseJoinColumns = @JoinColumn(name = "projectId"))
    @Cascade(org.hibernate.annotations.CascadeType.SAVE_UPDATE)
    private Collection<Project> projects = new LinkedHashSet<>();

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public Collection<Project> getProjects() {
        return projects;
    }

    public void setProjects(Collection<Project> projects) {
        this.projects = projects;
    }
}
