package net.sf.gilead.core.hibernate;

import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;

import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.ManagedType;

import org.hibernate.Hibernate;
import org.hibernate.HibernateException;
import org.hibernate.ObjectNotFoundException;
import org.hibernate.Session;
import org.hibernate.SessionException;
import org.hibernate.SessionFactory;
import org.hibernate.collection.spi.AbstractPersistentCollection;
import org.hibernate.collection.spi.PersistentBag;
import org.hibernate.collection.spi.PersistentList;
import org.hibernate.collection.spi.PersistentMap;
import org.hibernate.collection.spi.PersistentSet;
import org.hibernate.collection.spi.PersistentSortedMap;
import org.hibernate.collection.spi.PersistentSortedSet;
import org.hibernate.collection.spi.PersistentCollection;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.internal.SessionFactoryImpl;
import org.hibernate.persister.collection.CollectionPersister;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.query.Query;
import org.hibernate.tuple.IdentifierProperty;
import org.hibernate.tuple.entity.EntityMetamodel;
import org.hibernate.type.CollectionType;
import org.hibernate.type.CompositeType;
import org.hibernate.type.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.sf.beanlib.hibernate.UnEnhancer;
import net.sf.gilead.core.PersistenceUtil;
import net.sf.gilead.core.hibernate.exception.UnableToCreateEntityException;
import net.sf.gilead.core.serialization.SerializableId;
import net.sf.gilead.exception.ComponentTypeException;
import net.sf.gilead.exception.NotPersistentObjectException;
import net.sf.gilead.exception.TransientObjectException;
import net.sf.gilead.pojo.base.IUserType;
import net.sf.gilead.util.IntrospectionHelper;

/**
 * Persistent helper for Hibernate implementation Centralizes the SessionFactory and add some needed methods. Not really
 * a singleton, since there can be as many HibernateUtil instance as different sessionFactories
 *
 * @author BMARCHESSON
 */
public class HibernateUtil implements PersistenceUtil {

    private static final Logger LOGGER = LoggerFactory.getLogger(HibernateUtil.class);

    /**
     * Proxy id
     */
    private static final String ID = "id";

    /**
     * Persistent collection class name
     */
    private static final String CLASS_NAME = "class";

    /**
     * Underlying collection class name
     */
    private static final String UNDERLYING_COLLECTION = "underlying";

    /**
     * Persistent collection role
     */
    private static final String ROLE = "role";

    /**
     * Persistent collection PK ids
     */
    private static final String KEY = "key";

    /**
     * Persistent collection ids list
     */
    private static final String ID_LIST = "idList";

    /**
     * Persistent map values list
     */
    private static final String VALUE_LIST = "valueList";

    // ----
    // Attributes
    // ----
    /**
     * The pseudo unique instance of the singleton
     */
    private static HibernateUtil instance = null;

    /**
     * The Hibernate session factory
     */
    private SessionFactoryImpl sessionFactory;

    /**
     * The persistance map, with persistance status of all classes including persistent component classes
     */
    private Map<Class<?>, Boolean> persistenceMap;

    /**
     * The unenhancement map, used for performance purpose
     */
    private Map<Class<?>, Class<?>> unehancementMap;

    /**
     * The current opened session
     */
    private ThreadLocal<HibernateSession> sessionThread;

    private Session session;

    /**
     * @return the unique instance of the singleton
     */
    public static HibernateUtil getInstance() {
        if (instance == null) {
            instance = new HibernateUtil();
        }
        return instance;
    }

    /**
     * Empty constructor
     */
    public HibernateUtil() {
        this(null, null);
    }

    public HibernateUtil(Session session) {
        this(null, session);
    }

    /**
     * Complete constructor
     */
    public HibernateUtil(SessionFactory sessionFactory, Session session) {
        setSessionFactory(sessionFactory);
        this.session = session;
        sessionThread = new ThreadLocal<>();
        persistenceMap = Collections.synchronizedMap(new HashMap<Class<?>, Boolean>());
        unehancementMap = Collections.synchronizedMap(new HashMap<Class<?>, Class<?>>());

        // Filling persistence map with primitive types
        persistenceMap.put(Byte.class, false);
        persistenceMap.put(Short.class, false);
        persistenceMap.put(Integer.class, false);
        persistenceMap.put(Long.class, false);
        persistenceMap.put(Float.class, false);
        persistenceMap.put(Double.class, false);
        persistenceMap.put(Boolean.class, false);
        persistenceMap.put(String.class, false);
    }

    /**
     * @return the hibernate session Factory
     */
    public SessionFactory getSessionFactory() {
        return sessionFactory;
    }

    /**
     * @param sessionFactory the factory to set
     */
    public void setSessionFactory(SessionFactory sessionFactory) {
        if ((sessionFactory != null) && (sessionFactory instanceof SessionFactoryImpl == false)) {
            // Probably a Spring injected session factory
            sessionFactory = (SessionFactory) IntrospectionHelper.searchMember(SessionFactoryImpl.class, sessionFactory);
            if (sessionFactory == null) {
                throw new IllegalArgumentException("Cannot find Hibernate session factory implementation!");
            }
        }
        this.sessionFactory = (SessionFactoryImpl) sessionFactory;
    }

    @Override
    public Serializable getId(Object pojo) {
        return getId(pojo, getPersistentClass(pojo));
    }

    @Override
    public Serializable getId(Object pojo, Class<?> hibernateClass) {
        // Precondition checking
        if (sessionFactory == null) {
            throw new NullPointerException("No Hibernate Session Factory defined!");
        }

        // Verify if class is persistent.
        if (isPersistentClass(hibernateClass) == false) {
            LOGGER.debug("Class [{}] is not persistent.", hibernateClass.getName());
            throw new NotPersistentObjectException(pojo);
        }

        // Retrieve Class<?> hibernate metadata
        EntityPersister entityPersister = sessionFactory.getMetamodel().entityPersisters().get(getEntityName(hibernateClass, pojo));
        if (entityPersister == null) {
            // Component class (persistent but not metadata) : no associated id
            // So must be considered as transient
            throw new ComponentTypeException(pojo);
        }

        // Retrieve ID
        Serializable id = null;
        Class<?> pojoClass = getPersistentClass(pojo);
        if (hibernateClass.equals(pojoClass)) {
            LOGGER.debug("Same class for pojo [{}] and hibernateClass [{}].", pojo.getClass().getName(), hibernateClass.getName());
            if (pojo instanceof HibernateProxy) {
                // To prevent LazyInitialisationException
                LOGGER.debug("Get identifier by LazyInitializer [{}].", pojo.getClass().getName());
                id = (Serializable) ((HibernateProxy) pojo).getHibernateLazyInitializer().getIdentifier();
            } else {
                // Otherwise : use metada
                LOGGER.debug("Get identifier by ClassMetadata [{}].", pojo.getClass().getName());
                id = (Serializable) entityPersister.getClassMetadata().getIdentifier(pojo, (SharedSessionContractImplementor) session);
            }
        } else {
            LOGGER.debug("Get identifier of POJO [{}].", pojo.getClass().getName());
            // DTO case : invoke the method with the same name
            String property = entityPersister.getIdentifierPropertyName();

            try {
                // compute getter method name
                property = property.substring(0, 1).toUpperCase() + property.substring(1);
                String getter = "get" + property;

                // Find getter method
                Method method = pojoClass.getMethod(getter, (Class[]) null);
                if (method == null) {
                    throw new RuntimeException("Cannot find method " + getter + " for Class<?> " + pojoClass);
                }
                id = (Serializable) method.invoke(pojo, (Object[]) null);
            } catch (Exception ex) {
                throw new RuntimeException("Invocation exception ", ex);
            }
        }

        // Post condition checking
        if (isUnsavedValue(pojo, id, hibernateClass)) {
            throw new TransientObjectException(pojo);
        }

        return id;
    }

    @Override
    public boolean isPersistentPojo(Object pojo) {
        // Precondition checking
        if (pojo == null) {
            return false;
        }

        // Try to get the ID : if an exception is thrown the pojo is not persistent...
        try {
            getId(pojo);
            return true;
        } catch (TransientObjectException ex) {
            return false;
        } catch (NotPersistentObjectException ex) {
            return false;
        }
    }

    @Override
    public boolean isPersistentClass(Class<?> clazz) {
        // Precondition checking
        if (sessionFactory == null) {
            throw new NullPointerException("No Hibernate Session Factory defined!");
        }

        // Check proxy (based on beanlib Unenhancer class)
        clazz = getUnenhancedClass(clazz);

        // Look into the persistence map
        synchronized (persistenceMap) {
            Boolean persistent = persistenceMap.get(clazz);
            if (persistent != null) {
                return persistent.booleanValue();
            }
        }

        // First clall for this Class<?> : compute persistence class
        computePersistenceForClass(clazz);
        return persistenceMap.get(clazz).booleanValue();
    }

    @Override
    public Class<?> getUnenhancedClass(Class<?> clazz) {
        // Map checking
        Class<?> unenhancedClass = unehancementMap.get(clazz);
        if (unenhancedClass == null) {
            // Based on beanlib unEnhancer class
            unenhancedClass = UnEnhancer.unenhanceClass(clazz);
            unehancementMap.put(clazz, unenhancedClass);
        }
        return unenhancedClass;
    }

    @Override
    public boolean isEnhanced(Class<?> clazz) {
        // Compare class to unenhanced class
        return (clazz != getUnenhancedClass(clazz));
    }

    @Override
    @SuppressWarnings("resource")
    public void openSession() {
        // Precondition checking
        if (sessionFactory == null) {
            throw new NullPointerException("No Hibernate Session Factory defined!");
        }

        // Open a the existing session
        Session session = this.session;
        boolean created = false;
        try {
            if (session == null || !session.isConnected()) {
                session = sessionFactory.openSession();
                created = true;
            }
        } catch (HibernateException ex) {
            throw new SessionException("Could not open a session", ex);
        }

        // Store the session in ThreadLocal
        sessionThread.set(new HibernateSession(session, created));
    }

    @Override
    public void closeCurrentSession() {
        HibernateSession hSession = sessionThread.get();
        if (hSession != null) {
            // Only close session that we created
            if (hSession.created == true) {
                hSession.session.close();
            }
            sessionThread.remove();
        }
    }

    @Override
    @SuppressWarnings("resource")
    public Object load(Serializable id, Class<?> persistentClass) {
        // Unenhance persistent class if needed
        persistentClass = getUnenhancedClass(persistentClass);

        // Load the entity
        return getSession().get(persistentClass, id);
    }

    @Override
    public Map<String, Serializable> serializeEntityProxy(Object proxy) {
        // Precondition checking
        if (proxy == null) {
            return null;
        }

        // Serialize needed proxy informations
        Map<String, Serializable> result = new HashMap<>();
        result.put(CLASS_NAME, getUnenhancedClass(proxy.getClass()).getName());
        result.put(ID, getId(proxy));

        return result;
    }

    /**
     * Create a proxy for the argument class and id
     */
    @Override
    @SuppressWarnings("resource")
    public Object createEntityProxy(Map<String, Serializable> proxyInformations) {
        // Get needed proxy inforamtions
        Serializable id = proxyInformations.get(ID);
        String entityName = (String) proxyInformations.get(CLASS_NAME);

        // Create the associated proxy
        return getSession().load(entityName, id);
    }

    @Override
    public Map<String, Serializable> serializePersistentCollection(Collection<?> persistentCollection) {
        // Create serialization map
        Map<String, Serializable> result = new HashMap<>();

        // Get parameters
        AbstractPersistentCollection collection = (AbstractPersistentCollection) persistentCollection;
        result.put(CLASS_NAME, collection.getClass().getName());
        Collection<?> underlying = getUnderlyingCollection(persistentCollection);
        if (underlying != null) {
            result.put(UNDERLYING_COLLECTION, underlying.getClass().getName());
        }
        result.put(ROLE, collection.getRole());
        result.put(KEY, (Serializable) collection.getKey());

        // Store ids
        if (isInitialized(collection) == true) {
            result.put(ID_LIST, createIdList((Collection<?>) collection));
        }
        return result;
    }

    @Override
    public Map<String, Serializable> serializePersistentMap(Map<?, ?> persistentMap) {
        // Create serialization map
        Map<String, Serializable> result = new HashMap<>();

        // Get parameters
        AbstractPersistentCollection collection = (AbstractPersistentCollection) persistentMap;
        result.put(CLASS_NAME, collection.getClass().getName());
        result.put(ROLE, collection.getRole());
        result.put(KEY, (Serializable) collection.getKey());

        // Store ids
        if (isInitialized(collection) == true) {
            // Store keys
            ArrayList<SerializableId> keyList = createIdList(persistentMap.keySet());
            if (keyList != null) {
                result.put(ID_LIST, keyList);

                // Store values (only if keys are persistents)
                ArrayList<SerializableId> valueList = createIdList(persistentMap.values());
                result.put(VALUE_LIST, valueList);
            }
        }
        return result;
    }

    /**
     * Create a persistent collection
     *
     * @param proxyInformations serialized proxy informations
     * @param underlyingMap the filled underlying collection
     * @return
     */
    @Override
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public Map<?, ?> createPersistentMap(Object parent, Map<String, Serializable> proxyInformations, Map<?, ?> underlyingMap) {
        // Create original map
        Map<?, ?> originalMap = createOriginalMap(proxyInformations, underlyingMap);

        // Create collection for the class name
        String className = (String) proxyInformations.get(CLASS_NAME);

        SharedSessionContractImplementor session = (SharedSessionContractImplementor) getSession();
        PersistentCollection collection = null;
        if (PersistentMap.class.getName().equals(className)) {
            // Persistent map creation
            if (originalMap == null) {
                collection = new PersistentMap(session);
            } else {
                collection = new PersistentMap(session, originalMap);
            }
        } else if (PersistentSortedMap.class.getName().equals(className)) {
            // Persistent map creation
            if (originalMap == null) {
                collection = new PersistentSortedMap();
            } else {
                collection = new PersistentSortedMap(session, (SortedMap<?, ?>) originalMap);
            }
        } else {
            throw new RuntimeException("Unknown persistent map class name : " + className);
        }

        // Fill with serialized parameters
        String role = (String) proxyInformations.get(ROLE);
        CollectionPersister collectionPersister = sessionFactory.getMetamodel().collectionPersister(role);

        Serializable snapshot = null;
        if (originalMap != null) {
            // Create snapshot
            snapshot = collection.getSnapshot(collectionPersister);
        }

        collection.setSnapshot(proxyInformations.get(KEY), role, snapshot);

        // Owner
        collection.setOwner(parent);

        // Update persistent collection
        if (areDifferent(originalMap, underlyingMap)) {
            if (originalMap != null) {
                ((Map) collection).clear();
            }

            if (underlyingMap != null) {
                ((Map) collection).putAll(underlyingMap);
            }

            collection.dirty();
        }

        // In Hibernate 5.4 causes NullPointer in some cases
        // // Associated the collection to the persistence context
        // if (isInitialized(proxyInformations) == true) {
        // session.getPersistenceContext().addInitializedDetachedCollection(collectionPersister, collection);
        // } else {
        // session.getPersistenceContext().addUninitializedDetachedCollection(collectionPersister, collection);
        // }

        return (Map<?, ?>) collection;
    }

    /**
     * Create a persistent collection
     *
     * @param proxyInformations serialized proxy informations
     * @param underlyingCollection the filled underlying collection
     * @return
     */
    @Override
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public Collection<?> createPersistentCollection(Object parent, Map<String, Serializable> proxyInformations,
            Collection<?> underlyingCollection) {
        try {
            // Re-create original collection
            Collection<?> originalCollection = createOriginalCollection(proxyInformations, underlyingCollection);

            // Create Persistent collection for the class name
            String className = (String) proxyInformations.get(CLASS_NAME);

            SharedSessionContractImplementor session = (SharedSessionContractImplementor) getSession();
            PersistentCollection collection = null;
            if (PersistentBag.class.getName().equals(className)) {
                // Persistent bag creation
                if (originalCollection == null) {
                    collection = new PersistentBag(session);
                } else {
                    collection = new PersistentBag(session, originalCollection);
                }
            } else if (PersistentList.class.getName().equals(className)) {
                // Persistent list creation
                if (originalCollection == null) {
                    collection = new PersistentList(session);
                } else {
                    collection = new PersistentList(session, (List<?>) originalCollection);
                }
            } else if (PersistentSet.class.getName().equals(className)) {
                // Persistent set creation
                if (originalCollection == null) {
                    collection = new PersistentSet(session);
                } else {
                    collection = new PersistentSet(session, (Set<?>) originalCollection);
                }
            } else if (PersistentSortedSet.class.getName().equals(className)) {
                // Persistent sorted set creation
                if (originalCollection == null) {
                    collection = new PersistentSortedSet();
                } else {
                    collection = new PersistentSortedSet(session, (SortedSet<?>) originalCollection);
                }
            } else {
                throw new RuntimeException("Unknown persistent collection class name : " + className);
            }

            // Fill with serialized parameters
            String role = (String) proxyInformations.get(ROLE);
            CollectionPersister collectionPersister = sessionFactory.getMetamodel().collectionPersister(role);

            Serializable snapshot = null;
            if (originalCollection != null) {
                // Create snapshot
                snapshot = collection.getSnapshot(collectionPersister);

            }

            collection.setSnapshot(proxyInformations.get(KEY), role, snapshot);

            // Owner
            collection.setOwner(parent);

            // Update persistent collection
            if (areDifferent(originalCollection, underlyingCollection)) {
                if (originalCollection != null) {
                    ((Collection) collection).removeAll(originalCollection);
                }

                if (underlyingCollection != null) {
                    for (Object item : underlyingCollection) {
                        ((Collection) collection).add(item);
                    }
                }

                collection.dirty();
            }

            // In Hibernate 5.4 causes NullPointer in some cases
            // Associated the collection to the persistence context
            // if (isInitialized(proxyInformations) == true) {
            // session.getPersistenceContext().addInitializedDetachedCollection(collectionPersister, collection);
            // }
            // else {
            // session.getPersistenceContext().addUninitializedDetachedCollection(collectionPersister, collection);
            // }

            return (Collection<?>) collection;
        } catch (UnableToCreateEntityException ex) {
            // unable to re-create persistent collection (embeddable items) : load it
            LOGGER.warn("Unable to re-create persistent collection of not persistent items, loading it...");

            String role = proxyInformations.get(ROLE).toString();
            role = role.substring(role.lastIndexOf(".") + 1);

            Collection<?> collection = loadPersistentCollection(parent, role);
            collection.clear();
            collection.addAll((Collection) underlyingCollection);

            return collection;
        }
    }

    private Collection<?> loadPersistentCollection(Object entity, String collectionName) {
        Object loaded = loadAssociation(entity.getClass(), getId(entity), collectionName);

        // Get getter for the property
        Object association = null;
        try {
            Method reader = IntrospectionHelper.getReaderMethodForProperty(entity.getClass(), collectionName);
            association = reader.invoke(loaded, (Object[]) null);
        } catch (Exception ex) {
            throw new RuntimeException("Error during lazy loading invocation !", ex);
        }
        return (Collection<?>) association;
    }

    @Override
    public boolean isPersistentCollection(Class<?> collectionClass) {
        return (PersistentCollection.class.isAssignableFrom(collectionClass));
    }

    @Override
    public Collection<?> getUnderlyingCollection(Collection<?> collection) {
        // Precondition checking
        if ((collection == null) || (isPersistentCollection(collection.getClass()) == false)) {
            return collection;
        }

        // Persistent collection handling
        if (collection instanceof PersistentSet) {
            // Get the 'set' attribute
            try {
                Field setField = PersistentSet.class.getDeclaredField("set");
                setField.setAccessible(true);
                return (Collection<?>) setField.get(collection);
            } catch (Exception e) {
                // Should not happen
                throw new RuntimeException(e);
            }
        } else if (collection instanceof PersistentList) {
            // Get the 'list' attribute
            try {
                Field setField = PersistentList.class.getDeclaredField("list");
                setField.setAccessible(true);
                return (Collection<?>) setField.get(collection);
            } catch (Exception e) {
                // Should not happen
                throw new RuntimeException(e);
            }
        } else if (collection instanceof PersistentBag) {
            // Get the 'bag' attribute
            try {
                Field setField = PersistentBag.class.getDeclaredField("bag");
                setField.setAccessible(true);
                return (Collection<?>) setField.get(collection);
            } catch (Exception e) {
                // Should not happen
                throw new RuntimeException(e);
            }
        } else {
            // Not implemented
            LOGGER.warn("Unimplemented collection type :" + collection.getClass());
            return null;
        }
    }

    @Override
    public boolean isPersistentMap(Class<?> collectionClass) {
        return (PersistentMap.class.isAssignableFrom(collectionClass));
    }

    @Override
    public boolean isInitialized(Object proxy) {
        return Hibernate.isInitialized(proxy);
    }

    @Override
    public void initialize(Object proxy) {
        Hibernate.initialize(proxy);
    }

    /**
     * Flush pending modifications if needed
     */
    @Override
    @SuppressWarnings("resource")
    public void flushIfNeeded() {
        Session session = getCurrentSession();
        if (session != null) {
            LOGGER.trace("Flushing session !");
            session.flush();
        }
    }

    @Override
    @SuppressWarnings({ "unchecked", "resource" })
    public Object loadAssociation(Class<?> parentClass, Serializable parentId, String propertyName) {
        // Create query
        StringBuilder queryString = new StringBuilder();
        queryString.append("SELECT item FROM ");
        queryString.append(parentClass.getSimpleName());
        queryString.append(" item LEFT OUTER JOIN FETCH item.");
        queryString.append(propertyName);
        queryString.append(" WHERE item.id = :id");

        // Fill query
        Session session = getSession();
        Query<Object> query = session.createQuery(queryString.toString());
        query.setParameter("id", parentId);

        // Execute query
        return query.uniqueResult();
    }

    @Override
    @SuppressWarnings({ "unchecked", "resource" })
    public List<Object> executeQuery(String query, List<Object> parameters) {
        LOGGER.debug("Executing query [{}]", query);

        // Fill query
        Session session = getSession();
        Query<Object> hqlQuery = session.createQuery(query);

        // Fill parameters
        if (parameters != null) {
            for (int index = 0; index < parameters.size(); index++) {
                hqlQuery.setParameter(index, parameters.get(index));
            }
        }

        // Execute query
        return hqlQuery.list();
    }

    @Override
    @SuppressWarnings({ "unchecked", "resource" })
    public List<Object> executeQuery(String query, Map<String, Object> parameters) {
        LOGGER.trace("Executing query [{}].", query);

        // Fill query
        Session session = getSession();
        Query<Object> hqlQuery = session.createQuery(query);

        // Fill parameters
        if (parameters != null) {
            for (Map.Entry<String, Object> parameter : parameters.entrySet()) {
                hqlQuery.setParameter(parameter.getKey(), parameter.getValue());
            }
        }

        // Execute query
        return hqlQuery.list();
    }

    /**
     * Compute embedded persistence (Component, UserType) for argument class
     */
    private void computePersistenceForClass(Class<?> clazz) {
        // Precondition checking
        synchronized (persistenceMap) {
            if (persistenceMap.get(clazz) != null) {
                // already computed
                return;
            }
        }

        // Get associated metadata
        List<String> entityNames = getEntityNamesFor(clazz);
        if ((entityNames == null) || (entityNames.isEmpty() == true)) {
            // Not persistent : check implemented interfaces (they can be declared as persistent !!)
            Class<?>[] interfaces = clazz.getInterfaces();
            if (interfaces != null) {
                for (Class<?> inter : interfaces) {
                    if (isPersistentClass(inter)) {
                        markClassAsPersistent(clazz, true);
                        return;
                    }
                }
            }

            // Not persistent and no persistent interface!
            markClassAsPersistent(clazz, false);
            return;
        }

        // Persistent class
        markClassAsPersistent(clazz, true);

        // Look for component classes
        for (String entityName : entityNames) {
            Type[] types = sessionFactory.getMetamodel().entityPersister(entityName).getPropertyTypes();
            for (Type type : types) {
                LOGGER.debug("Scanning type [{}] from [{}].", type.getName(), clazz);
                computePersistentForType(type);
            }
        }
    }

    /**
     * Mark class as persistent or not
     *
     * @param clazz
     * @param persistent
     */
    private void markClassAsPersistent(Class<?> clazz, boolean persistent) {
        if (persistent) {
            LOGGER.debug("Marking [{}] as persistent", clazz);
        } else {
            LOGGER.debug("Marking [{}] as not persistent", clazz);
        }
        synchronized (persistenceMap) {
            // Debug check
            if (persistenceMap.get(clazz) == null) {
                persistenceMap.put(clazz, persistent);
            } else {
                // Check persistence information
                if (persistent != persistenceMap.get(clazz).booleanValue()) {
                    throw new RuntimeException("Invalid persistence state for " + clazz);
                }
            }
        }
    }

    /**
     * Compute persistent for Hibernate type
     *
     * @param type
     */
    private void computePersistentForType(Type type) {
        // Precondition checking
        synchronized (persistenceMap) {
            if (persistenceMap.get(type.getReturnedClass()) != null) {
                // already computed
                return;
            }
        }

        LOGGER.debug("Scanning type [{}]", type.getName());

        if (type.isComponentType()) {
            // Add the Class to the persistent map
            LOGGER.debug("Type [{}] is component type", type.getName());

            markClassAsPersistent(type.getReturnedClass(), true);

            Type[] subtypes = ((CompositeType) type).getSubtypes();
            for (Type subtype : subtypes) {
                computePersistentForType(subtype);
            }
        } else if (IUserType.class.isAssignableFrom(type.getReturnedClass())) {
            // Add the Class to the persistent map
            LOGGER.debug("Type [{}] is user type", type.getName());

            markClassAsPersistent(type.getReturnedClass(), true);
        } else if (type.isCollectionType()) {
            // Collection handling
            LOGGER.debug("Type [{}] is collection type", type.getName());
            computePersistentForType(((CollectionType) type).getElementType(sessionFactory));
        } else if (type.isEntityType()) {
            LOGGER.debug("Type [{}] is entity type", type.getName());
            computePersistenceForClass(type.getReturnedClass());
        }
    }

    /**
     * Create a list of serializable ID for the argument collection
     *
     * @param collection
     * @return
     */
    private ArrayList<SerializableId> createIdList(@SuppressWarnings("rawtypes") Collection collection) {
        // Precondition checking
        if (collection == null) {
            return null;
        }

        int size = collection.size();
        ArrayList<SerializableId> idList = new ArrayList<>(size);

        @SuppressWarnings("unchecked")
        Iterator<Object> iterator = collection.iterator();
        while (iterator.hasNext()) {
            Object item = iterator.next();
            if (item != null) {
                SerializableId id;

                if (isPersistentPojo(item)) {
                    id = serializePersistentEntity(item);
                } else {
                    id = serializeNotPersistentEntity(item);
                }

                idList.add(id);
            }
        }

        if (idList.isEmpty()) {
            return null;
        } else {
            return idList;
        }
    }

    /**
     * Serialize a persistent entity to a SerializableId
     *
     * @param item
     * @return the generated SerializableId
     */
    private SerializableId serializePersistentEntity(Object item) {
        SerializableId result = new SerializableId();
        result.setEntityName(getEntityName(getPersistentClass(item), item));
        result.setId(getId(item));

        return result;
    }

    /**
     * Serialize a not persistent entity to a SerializableId
     *
     * @param item
     * @return the generated SerializableId
     */
    @SuppressWarnings("rawtypes")
    private SerializableId serializeNotPersistentEntity(Object item) {
        SerializableId result = new SerializableId();
        Class<?> itemClass = item.getClass();

        result.setEntityName(itemClass.getName());

        // Special String and Number handling
        if (Number.class.isAssignableFrom(itemClass) || String.class.equals(itemClass)) {
            result.setValue(item.toString());
        }
        if (itemClass.isEnum()) {
            result.setValue(((Enum) item).name());
        } else {
            // No idea of what it is...
            // result.setValue(Integer.toString(item.hashCode()));
        }

        return result;
    }

    /**
     * Check if the id equals the unsaved value or not
     *
     * @param pojo
     * @return
     */
    private boolean isUnsavedValue(Object pojo, Serializable id, Class<?> persistentClass) {
        // Precondition checking
        if (id == null) {
            return true;
        }

        if(id instanceof Integer) {
            if(((Integer)id)==0) {
                return true;
            }
        }

        // Get unsaved value from entity metamodel
        EntityPersister entityPersister = sessionFactory.getMetamodel().entityPersister(getEntityName(persistentClass, pojo));
        EntityMetamodel metamodel = entityPersister.getEntityMetamodel();
        IdentifierProperty idProperty = metamodel.getIdentifierProperty();
        Boolean result = idProperty.isVirtual(); //getUnsavedValue().isUnsaved(id);

        if (result == null) {
            // Unsaved value undefined
            return false;
        } else {
            return result.booleanValue();
        }
    }

    /**
     * Return the underlying persistent class
     *
     * @param pojo
     * @return
     */
    private Class<?> getPersistentClass(Object pojo) {
        if (pojo instanceof HibernateProxy) {
            HibernateProxy proxy = (HibernateProxy) pojo;
            if (proxy.getHibernateLazyInitializer() != null) {
                return proxy.getHibernateLazyInitializer().getPersistentClass();
            }
        }

        // Just return the class
        return pojo.getClass();
    }

    /**
     * (Re)create the original collection
     *
     * @param proxyInformations
     * @param collection
     */
    @SuppressWarnings("unchecked")
    private <T> Collection<T> createOriginalCollection(Map<String, Serializable> proxyInformations, Collection<T> collection) {
        try {
            // Create base collection
            Class<?> collectionClass;
            if (collection == null) {
                // collection have been nullified on client side
                //
                String collectionClassName = (String) proxyInformations.get(UNDERLYING_COLLECTION);
                if (collectionClassName == null) {
                    // There collection was empty when serialized
                    return null;
                }
                collectionClass = Thread.currentThread().getContextClassLoader().loadClass(collectionClassName);
            } else {
                collectionClass = collection.getClass();
            }
            Collection<T> original = (Collection<T>) collectionClass.newInstance();

            // Fill original collection
            ArrayList<SerializableId> idList = (ArrayList<SerializableId>) proxyInformations.get(ID_LIST);
            if (idList != null) {
                // Create map(ID -> entity)
                Map<Serializable, T> collectionMap = createCollectionMap(collection);

                // Fill snapshot
                for (SerializableId sid : idList) {
                    original.add(createOriginalEntity(sid, collectionMap));
                }
            }

            return original;
        } catch (UnableToCreateEntityException e) {
            throw e;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * (Re)create the original map
     *
     * @param proxyInformations
     * @param map
     */
    @SuppressWarnings("unchecked")
    private <K, V> Map<K, V> createOriginalMap(Map<String, Serializable> proxyInformations, Map<K, V> map) {
        try {
            ArrayList<SerializableId> keyList = (ArrayList<SerializableId>) proxyInformations.get(ID_LIST);
            if (keyList != null) {
                ArrayList<SerializableId> valueList = (ArrayList<SerializableId>) proxyInformations.get(VALUE_LIST);

                // Create maps(ID -> entity)
                Map<Serializable, K> keyMap = null;
                Map<Serializable, V> valueMap = null;
                if (map != null) {
                    keyMap = createCollectionMap(map.keySet());
                    valueMap = createCollectionMap(map.values());
                } else {
                    keyMap = new HashMap<>();
                    valueMap = new HashMap<>();
                }

                // Fill snapshot map
                Map<K, V> snapshot = new HashMap<>();
                for (SerializableId sid : keyList) {
                    snapshot.put(createOriginalEntity(sid, keyMap), createOriginalEntity(valueList.get(keyList.indexOf(sid)), valueMap));
                }

                return snapshot;
            } else {
                return null;
            }
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * Test if the two argument collection are the same or not
     *
     * @param coll1
     * @param coll2
     * @return
     */
    @SuppressWarnings("rawtypes")
    private boolean areDifferent(Collection coll1, Collection coll2) {
        // Precondition checking
        if (coll1 == null) {
            return (coll2 != null && !coll2.isEmpty());
        }

        if (coll2 == null) {
            return !coll1.isEmpty();
        }

        // Size comparison
        if (coll1.size() != coll2.size()) {
            return true;
        }

        // Item comparison
        if ((coll1 instanceof List) || (coll1 instanceof SortedSet)) {
            // Compare content *and* order
            Object[] array1 = coll1.toArray();
            Object[] array2 = coll2.toArray();

            for (int index = 0; index < array1.length; index++) {
                if (array1[index] != array2[index]) {
                    return true;
                }
            }
        } else {
            // No order : just compare contents
            for (Object item : coll1) {
                if (coll2.contains(item) == false) {
                    return true;
                }
            }
        }
        // Same collections
        return false;
    }

    /**
     * Test if the two argument collection are the same or not
     *
     * @param map1
     * @param map2
     * @return
     */
    @SuppressWarnings("rawtypes")
    private boolean areDifferent(Map map1, Map map2) {
        // Precondition checking
        if (map1 == null) {
            return (map2 != null && !map2.isEmpty());
        }

        if (map2 == null) {
            return !map1.isEmpty();
        }

        // Size comparison
        if (map1.size() != map2.size()) {
            return true;
        }

        // Item comparison
        // No order : just compare contents
        for (Object key : map1.keySet()) {
            if (map2.containsKey(key) == false) {
                return true;
            }

            // Compare values
            Object value1 = map1.get(key);
            Object value2 = map2.get(key);
            if (value1.equals(value2) == false) {
                return true;
            }
        }

        // Same maps
        return false;
    }

    /**
     * Create an entity from its serializable id. The entity is taken from the argument map in priority.
     *
     * @param sid
     * @return
     */
    @SuppressWarnings("unchecked")
    private <T> T createOriginalEntity(SerializableId sid, Map<Serializable, T> collectionMap) {
        // Precondition checking
        T entity = null;
        if (sid.getId() != null) {
            // Is the entity still present ?
            entity = collectionMap.get(sid.getId());
            if (entity == null) {
                // deleted item
                try {
                    entity = (T) createPersistentEntity(sid);
                } catch (ObjectNotFoundException ex) {
                    // The data has already been deleted, just remove it from the collection
                    LOGGER.debug("Deleted entity: " + sid + " cannot be retrieved from DB and thus added to snapshot", ex);
                }
            }
        } else {
            entity = collectionMap.get(sid.getValue());
            if (entity == null) {
                // deleted item
                entity = (T) createNotPersistentEntity(sid);
            }
        }

        return entity;
    }

    /**
     * Create an entity back from its serializable id
     */
    @SuppressWarnings("resource")
    private Object createPersistentEntity(SerializableId sid) {
        return getSession().load(sid.getEntityName(), sid.getId());
    }

    /**
     * Create a not persistent entity (if possible !) back from its serializable id
     */
    @SuppressWarnings("rawtypes")
    private Object createNotPersistentEntity(SerializableId sid) {
        if (sid.getValue() == null) {
            // Did not k nwo how to serialize it...
            throw new UnableToCreateEntityException();
        }
        try {
            Class<?> clazz = Class.forName(sid.getEntityName());
            if (Number.class.isAssignableFrom(clazz)) {
                // Special case for numbers (no empty constructor defined)
                Constructor<?> ctor = clazz.getConstructor(new Class[] { String.class });
                return ctor.newInstance(sid.getValue());
            } else if (clazz.equals(String.class)) {
                return sid.getValue();
            } else if (clazz.isEnum()) {
                Method valuesMethod = clazz.getMethod("values", new Class[0]);
                Object[] values = (Object[]) valuesMethod.invoke(null);
                for (Object enumValue : values) {
                    if (((Enum) enumValue).name().equals(sid.getValue())) {
                        return enumValue;
                    }
                }

                // not found !
                throw new RuntimeException("Unexpected value for an enum SerializableId: " + sid.getValue());
            } else {
                // should not happen
                throw new RuntimeException("Unexpected not persistent entity type : " + sid.getEntityName());
            }
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * Create a collection map
     *
     * @param <T>
     * @param collection
     * @return
     */
    private <T> Map<Serializable, T> createCollectionMap(Collection<T> collection) {
        Map<Serializable, T> collectionMap = new HashMap<>();
        if (collection != null) {
            for (T item : collection) {
                if (item != null) {
                    try {
                        collectionMap.put(getId(item), item);
                    } catch (NotPersistentObjectException ex) {
                        // not hibernate entity : use hashcode instead
                        collectionMap.put(item.hashCode(), item);
                    } catch (TransientObjectException ex) {
                        // transient entity : use hashcode instead
                        collectionMap.put(item.hashCode(), item);
                    }
                }
            }
        }

        return collectionMap;
    }

    /**
     * @return the current session (open a new one if needed)
     */
    private Session getSession() {
        HibernateSession hSession = sessionThread.get();
        if (hSession == null) {
            openSession();
            hSession = sessionThread.get();
        }
        return hSession.session;
    }

    /**
     * Return the already opened session (returns null if none is opened)
     */
    protected Session getCurrentSession() {
        // Precondition checking
        if (sessionFactory == null) {
            throw new NullPointerException("No Hibernate Session Factory defined !");
        }

        // Open the existing session
        Session session = null;
        try {
            session = sessionFactory.getCurrentSession();
            if (session.isConnected() == false) {
                return null;
            }
        } catch (HibernateException ex) {
            LOGGER.trace("Exception during getCurrentSession", ex);
            return null;
        }

        return session;
    }

    /**
     * Get Hibernate class metadata
     *
     * @param clazz
     * @return
     */
    @SuppressWarnings("resource")
    private String getEntityName(Class<?> clazz, Object pojo) {
        // Direct metadata search
        ManagedType<?> metadata = sessionFactory.getMetamodel().managedType(clazz);
        if (metadata != null) {
            LOGGER.debug("Found metadata for class [{}] in managed types.", clazz.getName());
            return metadata.getJavaType().getName();
        } else {
            LOGGER.debug("Not found metadata for class [{}] in managed types.", clazz.getName());
        }

        // Iterate over all metadata to prevent entity name bug.
        List<String> entityNames = getEntityNamesFor(clazz);

        // check entity names
        if (entityNames.isEmpty()) {
            // Not found
            return getUnenhancedClass(clazz).getName();
        } else if (entityNames.size() == 1) {
            // Only one entity name
            return entityNames.get(0);
        }

        // More than one entity name : need pojo to know which one is the right one
        if (pojo != null) {
            // Get entity name
            return ((SessionImplementor) getSession()).bestGuessEntityName(pojo);
        } else {
            throw new NullPointerException("Missing pojo for entity name retrieving!");
        }
    }

    /**
     * @return all possible entity names for the argument class.
     */
    private List<String> getEntityNamesFor(Class<?> clazz) {
        List<String> entityNames = new ArrayList<>();
        for (EntityType<?> type : sessionFactory.getMetamodel().getEntities()) {
            if (clazz.equals(type.getBindableJavaType())) {
                entityNames.add(type.getBindableJavaType().getName());
            }
        }

        return entityNames;
    }
}

/**
 * Structure for Hibernate session management
 *
 * @author bruno.marchesson
 */
class HibernateSession {

    public Session session;

    public boolean created;

    public Map<Object, Object> clonedMap;

    public HibernateSession(Session session, boolean created) {
        this.session = session;
        this.created = created;
        this.clonedMap = new HashMap<>();
    }
}
