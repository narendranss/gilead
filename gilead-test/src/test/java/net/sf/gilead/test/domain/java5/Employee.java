package net.sf.gilead.test.domain.java5;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import net.sf.gilead.test.domain.interfaces.IEmployee;

/**
 * Employee class. Subclass of User
 * 
 * @author bruno.marchesson
 */
@Entity
@Table(name = "employee")
public class Employee extends User implements IEmployee {

    private static final long serialVersionUID = -2294737766711898873L;

    private String email;

    @Override
    @Column(name = "EMAIL", length = 45)
    public String getEmail() {
        return email;
    }

    @Override
    public void setEmail(String email) {
        this.email = email;
    }
}