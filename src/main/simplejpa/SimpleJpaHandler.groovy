/*
 * Copyright 2013 Jocki Hendry.
 *
 * Licensed under the Apache License, Version 2.0 (the 'License');
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an 'AS IS' BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package simplejpa

import org.codehaus.groovy.runtime.metaclass.ConcurrentReaderHashMap
import org.slf4j.*
import simplejpa.transaction.EntityManagerLifespan
import simplejpa.transaction.ReturnFailedSignal
import simplejpa.transaction.TransactionHolder
import javax.persistence.*
import griffon.util.*
import javax.persistence.criteria.CriteriaBuilder
import javax.persistence.criteria.CriteriaQuery
import javax.persistence.criteria.ParameterExpression
import javax.persistence.criteria.Predicate
import javax.persistence.criteria.Root
import javax.persistence.metamodel.EntityType
import javax.validation.ConstraintViolation
import javax.validation.ValidationException
import javax.validation.Validator
import javax.validation.groups.Default
import java.lang.reflect.Method

final class SimpleJpaHandler {

    private static Logger LOG = LoggerFactory.getLogger(SimpleJpaHandler)

    private static final PATTERN_FINDALLMODEL = /findAll([A-Z]\w*)/
    private static final PATTERN_FINDALLMODELFETCH = /findAll([A-Z]\w*)(Fetch|Load)([A-Z]\w*)/
    private static final PATTERN_FINDMODELBYID_NOTSOFTDELETED = /find([A-Z]\w*)ByIdNotSoftDeleted/
    private static final PATTERN_FINDMODELBYID = /find([A-Z]\w*)ById/
    private static final PATTERN_FINDMODELBYDSL = /find(All)?([A-Z]\w*)ByDsl((Fetch|Load)([A-Z]\w*))?/
    private static final PATTERN_FINDMODELBYATTRIBUTE = /find(All)?([A-Z]\w*)By([A-Z]\w*)/
    private static final PATTERN_FINDMODELBYATTRIBUTEFETCH = /find(All)?([A-Z]\w*)By([A-Z]\w*)(Fetch|Load)([A-Z]\w*)/
    private static final PATTERN_FINDMODELBY = /find(All)?([A-Z]\w*)By(And|Or)/

    private static final JPA_PROPERTIES_FETCH_GRAPH = 'javax.persistence.fetchgraph'
    private static final JPA_PROPERTIES_LOAD_GRAPH = 'javax.persistence.loadgraph'

    private static final int DEFAULT_PAGE_SIZE = 10

    final String prefix
    final String domainClassPackage
    final EntityManagerFactory emf
    final Validator validator
    final boolean alwaysExcludeSoftDeleted
    final boolean alwaysAllowDuplicate
    final EntityManagerLifespan entityManagerLifespan
    final FlushModeType defaultFlushMode


    final ConcurrentReaderHashMap mapEntityList = new ConcurrentReaderHashMap()
    private boolean convertEmptyStringToNull
    final ConcurrentReaderHashMap mapTransactionHolder = new ConcurrentReaderHashMap()

    public SimpleJpaHandler(EntityManagerFactory emf, Validator validator) {

        this.emf = emf
        this.validator = validator

        //
        // Initialize fields from related configurations
        //
        griffon.core.GriffonApplication app = griffon.util.ApplicationHolder.application
        this.entityManagerLifespan = ConfigUtils.getConfigValue(app.config,
            'griffon.simplejpa.entityManager.lifespan', 'TRANSACTION').toUpperCase()
        this.defaultFlushMode = ConfigUtils.getConfigValue(app.config,
            'griffon.simplejpa.entityManager.defaultFlushMode', 'AUTO').toUpperCase()
        this.prefix = ConfigUtils.getConfigValueAsString(app.config, 'griffon.simplejpa.finders.prefix', '')
        this.alwaysExcludeSoftDeleted = ConfigUtils.getConfigValueAsBoolean(app.config,
            'griffon.simplejpa.finders.alwaysExcludeSoftDeleted', false)
        this.alwaysAllowDuplicate = ConfigUtils.getConfigValueAsBoolean(app.config,
            'griffon.simplejpa.finders.alwaysAllowDuplicate', false)
        this.domainClassPackage = ConfigUtils.getConfigValueAsString(app.config, 'griffon.simplejpa.domain.package', 'domain')
        this.convertEmptyStringToNull = ConfigUtils.getConfigValueAsBoolean(app.config,
            'griffon.simplejpa.validation.convertEmptyStringToNull', false)

        if (LOG.isDebugEnabled()) {
            LOG.debug "SimpleJpaHandler initializd with the following configuration: \n" +
                "griffon.simplejpa.entityManager.lifespan = $entityManagerLifespan\n" +
                "griffon.simplejpa.entityManager.defaultFlushMode = $defaultFlushMode\n" +
                "griffon.simplejpa.method.prefix = $prefix\n" +
                "griffon.simplejpa.domain.package = $domainClassPackage\n" +
                "griffon.simplejpa.finders.alwaysExcludeSoftDeleted = $alwaysExcludeSoftDeleted\n" +
                "griffon.simplejpa.finders.alwaysAllowDuplicate = $alwaysAllowDuplicate\n" +
                "griffon.simplejpa.validation.convertEmptyStringToNull = $convertEmptyStringToNull\n"
        }

        //
        //  Add entity list (key = simple name to value = Class)
        //
        emf.metamodel.entities.each { EntityType e ->
            if (LOG.isDebugEnabled()) {
                LOG.debug "simple-jpa Entity List: Name [${e.name}] Java Class [${e.javaType}]"
            }
            mapEntityList[e.name] = e.javaType
        }

    }

    private void debugEntityManager() {
        if (LOG.isDebugEnabled()) {
            def result = mapTransactionHolder.collect{k,v -> "${k.id}=$v"}.join('\n')
            LOG.debug "List of cached EntityManager:\n$result"
        }
    }

    def getEntityManager = {
        LOG.debug "Retrieving current EntityManager from thread ${Thread.currentThread().id}..."
        EntityManager em = mapTransactionHolder.get(Thread.currentThread())?.em
        debugEntityManager()
        em
    }

    def createEntityManager = { TransactionHolder copy = null ->
        TransactionHolder th
        if (copy) {
            LOG.debug "Creating a new entity manager based on $copy..."
            th = new TransactionHolder(emf.createEntityManager(), copy)
        } else {
            LOG.debug "Creating a new entity manager..."
            EntityManager em = emf.createEntityManager()
            em.setFlushMode(defaultFlushMode)
            th = new TransactionHolder(em)
        }
        mapTransactionHolder.put(Thread.currentThread(), th)
        debugEntityManager()
        ApplicationHolder.application.event("simpleJpaCreateEntityManager", [th])
        th
    }

    def destroyEntityManager = {
        LOG.debug "Destroying all entity managers..."
        mapTransactionHolder.each { Thread k, TransactionHolder v ->
            if (v.em.isOpen()) {
                v.em.close()
            }
        }
        mapTransactionHolder.clear()
        debugEntityManager()
        ApplicationHolder.application.event("simpleJpaDestroyEntityManagers")
    }

    private configureCriteria(CriteriaBuilder cb, CriteriaQuery c, Root model, Map config) {
        LOG.debug "Processing configuration [$config]..."

        Predicate p = c.getRestriction()?: cb.conjunction()

        if ((alwaysExcludeSoftDeleted && !config.containsKey('excludeDeleted')) ||
            (config.containsKey('excludeDeleted') && config['excludeDeleted'])) {
                LOG.debug "Exclude soft deleted records..."
                p = cb.and(p, cb.equal(model.get("deleted"), "N"))
                c.where(p)
        }
        if (config['excludeSubclass']!=null) {
            String excludeSubclass = config['excludeSubclass']
            if (excludeSubclass=='*') {
                LOG.debug "Exclude all subclasses..."
                p = cb.and(p, cb.equal(model.type(), cb.literal(model.model.javaType)))
                c.where(p)
            } else {
                excludeSubclass.split(',').each {
                    Class subclass = mapEntityList[it.trim()]
                    LOG.debug "Exclude subclass: ${subclass}..."
                    p = cb.and(p, cb.notEqual(model.type(), cb.literal(subclass)))
                }
                c.where(p)
            }
        }

        if (config["orderBy"]!=null) {
            List orders = []
            List orderBy = config["orderBy"].tokenize(',')
            List orderDirection = config["orderDirection"]?.tokenize(',')

            orderBy.eachWithIndex { String fieldName, int index ->
                String direction = orderDirection?.get(index) ?: "asc"
                orders << cb."$direction"(model.get(fieldName))
            }

            LOG.debug "Applying order by [$orders]..."
            if (orders.size() > 0) c.orderBy(orders)
        }

        if (config['allowDuplicate']!=null) {
            c.distinct(!config['allowDuplicate'])
        } else {
            c.distinct(!alwaysAllowDuplicate)
        }
    }

    private Query configureQuery(Query query, Map config) {
        if (config["page"]!=null || config["pageSize"]!=null) {
            int page = config["page"] as Integer ?: 1
            page = (page - 1) >= 0 ? (page-1) : 0
            int pageSize = config["pageSize"] as Integer ?: DEFAULT_PAGE_SIZE
            LOG.debug "page = $page and pageSize = $pageSize"
            query.setFirstResult(page*pageSize)
            query.setMaxResults(pageSize)
        }
        if (config['flushMode']) {
            LOG.debug "flushMode = ${config['flushMode']}"
            query.setFlushMode(config['flushMode'])
        }
        if (config['fetchGraph'] && config['loadGraph']) {
            throw new IllegalArgumentException('fetchGraph and loadGraph can not be used together!')
        }
        if (config['fetchGraph']) {
            LOG.debug "fetchGraph = ${config['fetchGraph']}"
            if (config['fetchGraph'] instanceof String || config['fetchGraph'] instanceof GString) {
                query.setHint(JPA_PROPERTIES_FETCH_GRAPH, getEntityManager().getEntityGraph(config['fetchGraph']))
            } else {
                query.setHint(JPA_PROPERTIES_FETCH_GRAPH, config['fetchGraph'])
            }
        }
        if (config['loadGraph']) {
            LOG.debug "loadGraph = ${config['loadGraph']}"
            if (config['loadGraph'] instanceof String || config['fetchGraph'] instanceof GString) {
                query.setHint(JPA_PROPERTIES_LOAD_GRAPH, getEntityManager().getEntityGraph(config['loadGraph']))
            } else {
                query.setHint(JPA_PROPERTIES_LOAD_GRAPH, config['loadGraph'])
            }
        }
        query
    }

    private def filterResult(List results, boolean returnAll) {
        if (returnAll) {
            return results
        } else {
            if (results.isEmpty())
                return null
            else
                return results[0]
        }
    }

    private void setParameter(Query query, Map parameters ) {
        parameters.each { def parameter, value ->
            LOG.debug "Set query parameter: ${parameter} value: $value"
            if (value instanceof GString) value = value.toString()
            query.setParameter(parameter, value)
        }
    }

    def beginTransaction = { boolean resume = true, boolean newSession = false ->
        LOG.debug "Begin transaction from thread ${Thread.currentThread().id} (resume=$resume) (newSession=$newSession)..."
        if (newSession) {
            LOG.debug "Start a new session..."
            destroyEntityManager()
        }

        TransactionHolder th
        if (entityManagerLifespan==EntityManagerLifespan.TRANSACTION) {
            // always create new EntityManager for each transaction
            th = mapTransactionHolder.get(Thread.currentThread())
            if (th == null || th.resumeLevel==0) {
                th = createEntityManager()
            }
        } else if (entityManagerLifespan==EntityManagerLifespan.MANUAL) {
            // reuse previous EntityManager if possible
            th = mapTransactionHolder.get(Thread.currentThread())
            if (!th) {
                th = createEntityManager()
            }
        }
        if (th.beginTransaction(resume)) {
            ApplicationHolder.application.event("simpleJpaNewTransaction", [th])
        }
    }

    def closeAndRemoveCurrentEntityManager = {
        LOG.debug "Close EntityManager: Searching for TransactionHolder for thread ${Thread.currentThread()}"
        TransactionHolder th = mapTransactionHolder.get(Thread.currentThread())
        LOG.debug "Close EntityManager: Associated with TransactionHolder $th..."
        if (th) {
            ApplicationHolder.application.event("simpleJpaBeforeCloseEntityManager", [th])
            th.em.close()
            LOG.debug "Close EntityManager: EntityManager for $th is closed!"
            mapTransactionHolder.remove(Thread.currentThread())
            LOG.debug "Close EntityManager: EntityManager for thread ${Thread.currentThread()} is removed!"
        }
    }

    def commitTransaction = {
        LOG.debug "Commit Transaction: From thread ${Thread.currentThread()} (${Thread.currentThread().id})..."
        TransactionHolder th = mapTransactionHolder.get(Thread.currentThread())
        if (th?.commitTransaction()) {
            if (entityManagerLifespan==EntityManagerLifespan.TRANSACTION && th.resumeLevel==0) {
                LOG.debug "Commit Transaction: Closing EntityManager..."
                closeAndRemoveCurrentEntityManager()
            }
            ApplicationHolder.application.event("simpleJpaCommitTransaction", [th])
        } else {
            if (LOG.isWarnEnabled()) LOG.warn "Commit Transaction: TransactionHolder is null for ${Thread.currentThread()}!"
        }
    }

    def rollbackTransaction = {
        LOG.debug "Rollback Transaction: From thread ${Thread.currentThread()} (${Thread.currentThread().id})..."
        TransactionHolder th = mapTransactionHolder.get(Thread.currentThread())
        if (th) {
            LOG.debug "Rollback Transaction: Clearing EntityManager..."
            th.em.clear()
            if (th.rollbackTransaction()) {
                LOG.debug "Rollback Transaction: Closing EntityManager..."
                closeAndRemoveCurrentEntityManager()
                if (entityManagerLifespan==EntityManagerLifespan.MANUAL) {
                    LOG.debug "Rollback Transaction: Creating a new EntityManager..."
                    createEntityManager(th)
                }
                ApplicationHolder.application.event("simpleJpaRollbackTransaction", [th])
            }
        } else {
            if (LOG.isWarnEnabled()) LOG.warn "Rollback Transaction: TransactionHolder is null for ${Thread.currentThread()}!"
        }
    }

    def withTransaction = { Closure action ->
        action.delegate = this
        action.setResolveStrategy(Closure.DELEGATE_FIRST)
        executeInsideTransaction(action)
    }

    def executeInsideTransaction(Closure action) {
        boolean insideTransaction = true
        boolean isError = false
        def result
        if (!getEntityManager()?.transaction?.isActive()) {
            insideTransaction = false
            ApplicationHolder.application.event("simpleJpaBeforeAutoCreateTransaction")
            beginTransaction()
        }
        LOG.debug "Auto Create Transaction: Active [${!insideTransaction}]"
        try {
            result = action()
        } catch (Exception ex) {
            LOG.error "Auto Create Transaction: Active [${!insideTransaction}] Exception is raised", ex
            if (!insideTransaction) {
                isError = true
                rollbackTransaction()
            }
            throw ex
        } finally {
            LOG.debug "Auto Create Transaction: Active [${!insideTransaction}] Finally..."
            if (!insideTransaction && !isError) {
                LOG.debug "Auto Create Transaction: Active [${!insideTransaction}] Commiting transaction..."
                commitTransaction()
            }
        }
        LOG.debug "Auto Create Transaction: Active [${!insideTransaction}] Done"
        return result
    }

    def returnFailed = {
        throw new ReturnFailedSignal()
    }

    def executeQuery = { String jpql, Map config = [:], Map params = [:] ->
        LOG.debug "Executing query $jpql"
        executeInsideTransaction {
            Query query = configureQuery(getEntityManager().createQuery(jpql), config)
            params.each { k, v -> query.setParameter(k,v)}
            query.getResultList()
        }
    }

    def executeNativeQuery = { String sql, Map config = [:] ->
        LOG.debug "Executing native query $sql"
        executeInsideTransaction {
            configureQuery(getEntityManager().createNativeQuery(sql), config).getResultList()
        }
    }

    def findAllModel = { String model, Map additionalConfig = [:] ->
        return { Map config = [:] ->
            LOG.debug "Executing findAll$model with additionalConfig [$additionalConfig]"
            executeInsideTransaction {
                CriteriaBuilder cb = getEntityManager().getCriteriaBuilder()
                CriteriaQuery c = cb.createQuery()
                Root rootModel = c.from(mapEntityList[model])
                c.select(rootModel)

                config << additionalConfig
                configureCriteria(cb, c, rootModel, config)
                configureQuery(getEntityManager().createQuery(c), config).getResultList()
            }
        }
    }

    def findModelById = { String model, boolean notSoftDeleted ->
        def modelClass = mapEntityList[model]
        def idClass = emf.metamodel.entity(modelClass).idType.javaType

        return { id ->
            LOG.debug "Executing find$model for class $modelClass and id [$id]"
            executeInsideTransaction {
                Object object = getEntityManager().find(modelClass, idClass.newInstance(id))
                if (notSoftDeleted) {
                    if (object."deleted"=="Y") return null
                }
                object
            }
        }
    }

    def findByDsl = { Class modelClass, Map config = [:], Closure closure ->
        findModelByDsl(modelClass, false, config, closure)
    }

    def findAllByDsl = { Class modelClass, Map config = [:], Closure closure ->
        findModelByDsl(modelClass, true, config, closure)
    }

    def findModelByDsl = { Class modelClass, boolean returnAll, Map config = [:], Closure closure ->
        LOG.debug "Find entities by Dsl: model=$modelClass, returnAll=$returnAll, config=$config"
        executeInsideTransaction {
            CriteriaBuilder cb = getEntityManager().getCriteriaBuilder()
            CriteriaQuery c = cb.createQuery()
            Root rootModel = c.from(modelClass)
            c.select(rootModel)

            closure.setResolveStrategy(Closure.DELEGATE_FIRST)
            closure.delegate = new QueryDsl(cb: cb, rootModel: rootModel)
            closure.call()
            c.where(closure.delegate.criteria)

            configureCriteria(cb, c, rootModel, config)
            Query q = configureQuery(getEntityManager().createQuery(c), config)
            setParameter(q, closure.delegate.parameters)
            filterResult(q.resultList, returnAll)
        }
    }

    def findModelBy = { Class modelClass, boolean returnAll, boolean isAnd = true, Map args, Map config = [:] ->
        LOG.debug "Find entities by: model=$modelClass, returnAll=$returnAll, args=$args, confing=$config"
        executeInsideTransaction {
            CriteriaBuilder cb = getEntityManager().getCriteriaBuilder()
            CriteriaQuery c = cb.createQuery()
            Root rootModel = c.from(modelClass)
            c.select(rootModel)

            Predicate criteria
            if (isAnd) {
                criteria = cb.conjunction()
                args.each { key, value ->
                    ParameterExpression p = cb.parameter(rootModel.get(key).javaType, key)
                    criteria = cb.and(criteria, cb.equal(rootModel.get(key), p))
                }
            } else {
                criteria = cb.disjunction()
                args.each { key, value ->
                    ParameterExpression p = cb.parameter(rootModel.get(key).javaType, key)
                    criteria = cb.or(criteria, cb.equal(rootModel.get(key), p))
                }
            }

            c.where(criteria)
            configureCriteria(cb, c, rootModel, config)
            Query q = configureQuery(getEntityManager().createQuery(c), config)
            setParameter(q, args)
            filterResult(q.getResultList(), returnAll)
        }
    }

    def findByAnd = { Class modelClass, Map args, Map config = [:] ->
        findModelBy(modelClass, false, true, args, config)
    }

    def findAllByAnd = { Class modelClass, Map args, Map config = [:] ->
        findModelBy(modelClass, true, true, args, config)
    }

    def findByOr = { Class modelClass, Map args, Map config = [:] ->
        findModelBy(modelClass, false, false, args, config)
    }

    def findAllByOr = { Class modelClass, Map args, Map config = [:] ->
        findModelBy(modelClass, true, false, args, config)
    }

    public def parseFinder(String finder) {
        LOG.debug "Parsing $finder"
        List results = []
        finder.split('(And|Or)').each { String expr ->
            LOG.debug "Expression: $expr"
            int start = finder.indexOf(expr)
            int operStart
            String operName, fieldName
            int argsCount
            def availableOperators = QueryDsl.OPERATORS.keySet().toArray()
            for (int i=0; i<availableOperators.size(); i++) {
                if (expr.toLowerCase().endsWith(availableOperators[i].toLowerCase())) {
                    operStart = expr.toLowerCase().lastIndexOf(availableOperators[i].toLowerCase())
                    operName = QueryDsl.OPERATORS[availableOperators[i]].operation
                    argsCount = QueryDsl.OPERATORS[availableOperators[i]].argsCount
                    break
                }
            }
            if (operStart <= 0) {
                operName = 'equal'
                fieldName = GriffonNameUtils.uncapitalize(expr)
                argsCount = 1
            } else {
                fieldName = GriffonNameUtils.uncapitalize(expr.substring(0, operStart))
            }

            def whereExpr = [field: fieldName, oper: operName, argsCount: argsCount, isAnd: null, isOr: null]
            if (start > 0) {
                if (finder.substring(0, start).endsWith('And')) {
                    whereExpr.isAnd = true
                } else if (finder.substring(0, start).endsWith('Or')) {
                    whereExpr.isOr = true
                }
            }
            LOG.debug "Result: $whereExpr"
            results << whereExpr
        }
        results
    }

    def findModelByAttribute = { Class modelClass, boolean returnAll, List whereExprs, Map additionalConfig = [:], Object[] args ->
        def config = [:]
        if (args.length > 0 && args.last() instanceof Map) {
            config = args.last()
        }
        config << additionalConfig
        LOG.debug "Find entities by attribute: model=$modelClass, returnAll=$returnAll, whereExprs=$whereExprs, args=$args, config=$config"
        executeInsideTransaction {
            CriteriaBuilder cb = getEntityManager().getCriteriaBuilder()
            CriteriaQuery c = cb.createQuery()
            Root rootModel = c.from(modelClass)
            c.select(rootModel)

            Predicate p
            Map params = [:]
            int argsIndex = 0

            if (whereExprs.size()==1) {
                def arguments = [rootModel.get(whereExprs[0].field)]
                if (whereExprs[0].argsCount > 0) {
                    (0..whereExprs[0].argsCount-1).each {
                        Parameter param = cb.parameter(rootModel.get(whereExprs[0].field).javaType)
                        arguments << param
                        params[param] = args[argsIndex++]
                    }
                }
                p = cb."${whereExprs[0].oper}"(*arguments)
                c.where(p)
            } else {
                whereExprs.each { expr ->
                    def arguments = [rootModel.get(expr.field)]
                    if (expr.argsCount > 0) {
                        (0..expr.argsCount-1).each {
                            Parameter param = cb.parameter(rootModel.get(expr.field).javaType)
                            arguments << param
                            params[param] = args[argsIndex++]
                        }
                    }
                    Predicate p2 = cb."${expr.oper}"(*arguments)
                    if (expr.isAnd) {
                        p = cb.and(p, p2)
                    } else if (expr.isOr) {
                        p = cb.or(p, p2)
                    } else {
                        p = p2
                    }
                }
                c.where(p)
            }

            configureCriteria(cb, c, rootModel, config)
            Query q = configureQuery(getEntityManager().createQuery(c), config)
            setParameter(q, params)
            filterResult(q.resultList, returnAll)
        }
    }

    def executeNamedQuery = { String namedQuery, Map args, Map config = [:] ->
        LOG.debug "Executing named query: namedQuery=$namedQuery, args=$args, confing=$config"
        executeInsideTransaction {
            Query query = getEntityManager().createNamedQuery(namedQuery)
            args.each { key, value ->
                if (query.parameters.find { it.name == key }) {
                    query.setParameter(key, value)
                }
            }
            configureQuery(query, config).getResultList()
        }
    }

    def softDelete = { model ->
        LOG.debug "Executing softDelete for [$model]"
        executeInsideTransaction {
            EntityManager em = getEntityManager()
            if (!em.contains(model)) {
                model = em.merge(model)
            }
            model.deleted = "Y"
        }
    }

    def persist = { model ->
        LOG.debug "Executing persist for [$model]"
        executeInsideTransaction {
            EntityManager em = getEntityManager()
            em.persist(model)
        }
    }

    def merge = { model ->
        LOG.debug "Executing merge for [$model]"
        executeInsideTransaction {
            EntityManager em = getEntityManager()
            return em.merge(model)
        }
    }

    def remove = { model ->
        LOG.debug "Executing remove for [$model]"
        executeInsideTransaction {
            def persistedModel = model
            EntityManager em = getEntityManager()
            if (!em.contains(model)) {
                persistedModel = em.find(model.class, model.id)
                if (!persistedModel) {
                    persistedModel = em.merge(model)
                }
            }
            em.remove(persistedModel)
        }
    }

    def validate = { model, group = Default, viewModel = null ->
        LOG.debug "Validating model [$model] group [$group] viewModel [$viewModel]"

        boolean valid = true

        if (viewModel==null && delegate.model!=null) {
            viewModel = delegate.model
        }
        LOG.debug "View model to store validation constraint messages: [$viewModel]"

        if (viewModel?.hasError()) return false

        // Convert empty string to null if required
        if (convertEmptyStringToNull) {
            model.properties.each { k, v ->
                if (v instanceof String && v.isAllWhitespace()) {
                    model.putAt(k, null)
                }
            }
        }

        validator.validate(model, group).each { ConstraintViolation cv ->
            if (viewModel) {
                LOG.debug "Adding error path [${cv.propertyPath}] with message [${cv.message}]"
                viewModel.errors[cv.propertyPath.toString()] = cv.message
            } else {
                throw new ValidationException("Validation fail on ${cv.propertyPath}: ${cv.message}")
            }
            valid = false
        }

        valid
    }

    def methodMissingHandler = { String name, args ->

        LOG.debug "Searching for method [$name] and args [$args]"

        // Check for prefix
        if (prefix=="") {
            LOG.debug "No prefix is used for injected methods."
        }  else if (!name.startsWith(prefix)) {
            LOG.error "Missing method $name !"
            throw new MissingMethodException(name, delegate.class, (Object[]) args)
        }

        // Remove prefix
        StringBuffer fName = new StringBuffer()
        fName << name[Math.max(0, prefix.length())].toLowerCase()
        fName << name.substring(Math.max(1, prefix.length()+1))
        String nameWithoutPrefix = fName.toString()
        LOG.debug "Method name without prefix [$nameWithoutPrefix]"

        // Checking for injected methods
        switch(nameWithoutPrefix) {

            // findModelByIdNotSoftDeleted
            case ~PATTERN_FINDMODELBYID_NOTSOFTDELETED:
                def match = nameWithoutPrefix =~ PATTERN_FINDMODELBYID_NOTSOFTDELETED
                def modelName = match[0][1]
                LOG.debug "First match for model [$modelName] not soft deleted"

                Closure findModelByIdClosure = findModelById(modelName, true)
                delegate.metaClass."$name" = findModelByIdClosure
                return findModelByIdClosure.call(args)

            // findModelById
            case ~PATTERN_FINDMODELBYID:
                def match = (nameWithoutPrefix =~ PATTERN_FINDMODELBYID)
                def modelName = match[0][1]
                LOG.debug "First match for model [$modelName]"

                Closure findModelByIdClosure = findModelById(modelName, alwaysExcludeSoftDeleted)
                delegate.metaClass."$name" = findModelByIdClosure
                return findModelByIdClosure.call(args)

            // findModelByDsl
            case ~PATTERN_FINDMODELBYDSL:
                def match = (nameWithoutPrefix =~ PATTERN_FINDMODELBYDSL)
                def isReturnAll = match[0][1]!=null? true: false
                def modelName = match[0][2]
                def config = [:]
                if (match[0][3]) {
                    def graphOperation = match[0][4] == 'Fetch' ? 'fetchGraph': 'loadGraph'
                    def graphName = "$modelName.${match[0][5]}"
                    config.put(graphOperation, graphName)
                }

                LOG.debug "First match for model [$modelName] with implicit config $config"

                Class modelClass = mapEntityList[modelName]
                def action = { cfg = [:], closure ->
                    cfg << config
                    findModelByDsl(modelClass, isReturnAll, cfg, closure)
                }
                delegate.metaClass."$name" = action
                return action(*args)

            // findModelBy
            case ~PATTERN_FINDMODELBY:
                def match = (nameWithoutPrefix =~ PATTERN_FINDMODELBY)
                def isReturnAll = match[0][1]!=null? true: false
                def modelName = match[0][2]
                def isAnd = (match[0][3] == 'And')
                LOG.debug "First match for model [$modelName]"
                Class modelClass = mapEntityList[modelName]
                delegate.metaClass."$name" = { Object[] p -> findModelBy(modelClass, isReturnAll, isAnd, *p) }
                return findModelBy.call(modelClass, isReturnAll, isAnd, *args)

            // findModelByAttributeFetch
            case ~PATTERN_FINDMODELBYATTRIBUTEFETCH:
                def match = (nameWithoutPrefix =~ PATTERN_FINDMODELBYATTRIBUTEFETCH)
                def isReturnAll = match[0][1]!=null? true: false
                def modelName = match[0][2]
                def whereExprs = parseFinder(match[0][3])
                def graphOperation = match[0][4] == 'Fetch' ? 'fetchGraph': 'loadGraph'
                def graphName = "$modelName.${match[0][5]}"
                def config = [:]
                config.put(graphOperation, graphName)

                LOG.debug "First match for model [$modelName] with implicit config $config"

                Class modelClass = mapEntityList[modelName]
                delegate.metaClass."$name" = { Object[] p ->
                    findModelByAttribute(modelClass, isReturnAll, whereExprs, config, p)
                }
                return findModelByAttribute.call(modelClass, isReturnAll, whereExprs, config, args)

            // findModelByAttribute
            case ~PATTERN_FINDMODELBYATTRIBUTE:
                def match = (nameWithoutPrefix =~ PATTERN_FINDMODELBYATTRIBUTE)
                def isReturnAll = match[0][1]!=null? true: false
                def modelName = match[0][2]
                def whereExprs = parseFinder(match[0][3])
                LOG.debug "First match for model [$modelName]"
                Class modelClass = mapEntityList[modelName]
                delegate.metaClass."$name" = { Object[] p -> findModelByAttribute(modelClass, isReturnAll, whereExprs, p) }
                return findModelByAttribute.call(modelClass, isReturnAll, whereExprs, args)

            // findAllModelFetch
            case ~PATTERN_FINDALLMODELFETCH:
                def match = nameWithoutPrefix =~ PATTERN_FINDALLMODELFETCH
                def modelName = match[0][1]
                def graphOperation = match[0][2] == 'Fetch' ? 'fetchGraph': 'loadGraph'
                def graphName = "$modelName.${match[0][3]}"
                def config = [:]
                config.put(graphOperation, graphName)

                LOG.debug "First match for model [$modelName] with implicit config $config"

                Closure findAllModelClosure = findAllModel(modelName, config)
                delegate.metaClass."$name" = findAllModelClosure
                return findAllModelClosure.call(args)

            // findAllModel
            case ~PATTERN_FINDALLMODEL:
                def match = nameWithoutPrefix =~ PATTERN_FINDALLMODEL
                def modelName = match[0][1]
                LOG.debug "First match for model [$modelName]"

                Closure findAllModelClosure = findAllModel(modelName)
                delegate.metaClass."$name" = findAllModelClosure
                return findAllModelClosure.call(args)


            // Nothing found
            default:
                // Is this one of EntityManager's methods?
                Method method = EntityManager.methods.find { it.name == nameWithoutPrefix }
                if (method) {
                    LOG.debug "Executing EntityManager.${nameWithoutPrefix}"
                    executeInsideTransaction {
                        EntityManager em = getEntityManager()
                        return method.invoke(em, args)
                    }
                } else {
                    // No, this is unknown name
                    LOG.error "Missing method $name !"
                    throw new MissingMethodException(name, delegate.class, (Object[])args)
                }
        }
    }
}