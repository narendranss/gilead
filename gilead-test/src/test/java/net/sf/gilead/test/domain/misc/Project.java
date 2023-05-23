package net.sf.gilead.test.domain.misc;

import java.util.Collection;
import java.util.LinkedHashSet;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToMany;

import net.sf.gilead.pojo.java5.LightEntity;

@Entity
public class Project extends LightEntity {

    private static final long serialVersionUID = 8543210310893851159L;

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private long id;

    @ManyToMany(mappedBy = "projects")
    private Collection<Client> clients = new LinkedHashSet<>();

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public Collection<Client> getClients() {
        return clients;
    }

    public void setClients(Collection<Client> clients) {
        this.clients = clients;
    }
}