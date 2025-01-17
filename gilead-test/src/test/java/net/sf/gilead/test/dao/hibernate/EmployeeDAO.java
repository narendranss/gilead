package net.sf.gilead.test.dao.hibernate;

import org.hibernate.query.Query;
import org.hibernate.Session;
import org.hibernate.Transaction;

import net.sf.gilead.test.HibernateContext;
import net.sf.gilead.test.dao.IEmployeeDAO;
import net.sf.gilead.test.domain.interfaces.IEmployee;

/**
 * DAO for employee beans.
 * 
 * @author bruno.marchesson
 */
public class EmployeeDAO implements IEmployeeDAO {

    /**
     * Load the employee with the argument ID
     */
    @Override
    public IEmployee loadEmployee(Integer id) {
        Session session = null;
        Transaction transaction = null;
        try {
            // Get session
            session = HibernateContext.getSessionFactory().getCurrentSession();
            transaction = session.beginTransaction();

            // Create query
            Query query = session.createQuery("from Employee employee where employee.id=:id");
            query.setParameter("id", id);

            // Execute query
            IEmployee employee = (IEmployee) query.uniqueResult();
            transaction.commit();

            return employee;
        } catch (RuntimeException e) {
            // Rollback
            transaction.rollback();
            throw e;
        }
    }

    /**
     * Load the employee with the argument login
     */
    @Override
    public IEmployee searchEmployeeAndMessagesByLogin(String login) {
        Session session = null;
        Transaction transaction = null;
        try {
            // Get session
            session = HibernateContext.getSessionFactory().getCurrentSession();
            transaction = session.beginTransaction();

            // Create query
            StringBuffer hqlQuery = new StringBuffer();
            hqlQuery.append("from Employee employee");
            hqlQuery.append(" left join fetch employee.messageList");
            hqlQuery.append(" where employee.login=:login");

            // Fill query
            Query query = session.createQuery(hqlQuery.toString());
            query.setParameter("login", login);

            // Execute query
            IEmployee employee = (IEmployee) query.uniqueResult();
            transaction.commit();

            return employee;
        } catch (RuntimeException e) {
            // Rollback
            transaction.rollback();
            throw e;
        }
    }
}
