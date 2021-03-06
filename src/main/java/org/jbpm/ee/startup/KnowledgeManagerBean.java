package org.jbpm.ee.startup;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.ejb.DependsOn;
import javax.ejb.Singleton;
import javax.ejb.Startup;
import javax.inject.Inject;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.NoResultException;
import javax.persistence.Query;

import org.jbpm.ee.config.Configuration;
import org.jbpm.ee.exception.SessionException;
import org.jbpm.ee.persistence.KieBaseXProcessInstance;
import org.jbpm.ee.service.exception.InactiveProcessInstance;
import org.jbpm.ee.support.KieReleaseId;
import org.jbpm.runtime.manager.impl.RuntimeEnvironmentBuilder;
import org.kie.api.KieBase;
import org.kie.api.KieServices;
import org.kie.api.PropertiesConfiguration;
import org.kie.api.builder.KieScanner;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.manager.RuntimeEngine;
import org.kie.api.runtime.manager.RuntimeManager;
import org.kie.api.task.TaskService;
import org.kie.api.task.model.Task;
import org.kie.internal.runtime.manager.RuntimeEnvironment;
import org.kie.internal.runtime.manager.RuntimeManagerFactory;
import org.kie.internal.runtime.manager.context.ProcessInstanceIdContext;
import org.kie.internal.task.api.UserGroupCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts up and listens for changes to the change set.
 * @author bradsdavis
 *
 */
@Startup
@Singleton(name="KnowledgeAgentManager")
@DependsOn("ResourceManager")
public class KnowledgeManagerBean {
	private static final Logger LOG = LoggerFactory.getLogger(KnowledgeManagerBean.class);
	
	protected Map<KieReleaseId, KieContainer> containers;
	protected Map<KieReleaseId, KieScanner> scanners;
	
	protected Map<KieReleaseId, RuntimeManager> runtimeManagers;
	
	protected KieServices kieServices;
	
	@Inject
	@Configuration(value="scannerPollFrequency")
	protected Long scannerPollFrequency;
	
	@Inject
	protected EntityManagerFactory emf;
	
	@Inject
	protected EntityManager em;
	
	@Inject
	protected UserGroupCallback userGroupCallback;
	
	@Inject
	protected TaskService taskService;
	
	
	@PostConstruct
	private void setup() {
		 kieServices = KieServices.Factory.get();
		 containers = new ConcurrentHashMap<KieReleaseId, KieContainer>();
		 scanners = new ConcurrentHashMap<KieReleaseId, KieScanner>();
		 
		 runtimeManagers = new ConcurrentHashMap<KieReleaseId, RuntimeManager>();
	}

	@PreDestroy
	private void destroy() {
		LOG.info("Stopping jBPM Resource Change Listeners.");
		
		for(KieScanner scanner : scanners.values()) {
			scanner.stop();
		}
		
		//set for dispose.
		this.scanners = null;
	}

	protected KieContainer getKieContainer(KieReleaseId resourceKey) {
		
		if(!containers.containsKey(resourceKey)) {
			//create a new container.
			KieContainer kieContainer = kieServices.newKieContainer(resourceKey.toReleaseIdImpl());
			KieScanner kieScanner = kieServices.newKieScanner(kieContainer);
			kieScanner.start(scannerPollFrequency);
			
			//register the new container and scanner.
			this.containers.put(resourceKey, kieContainer);
			this.scanners.put(resourceKey, kieScanner);
		}
		return this.containers.get(resourceKey);
	}
	
	protected KieBase getKieBase(KieReleaseId resourceKey) {
		return getKieContainer(resourceKey).getKieBase();
	}	
	
	public RuntimeEnvironment getRuntimeEnvironment(KieReleaseId releaseId) {
		RuntimeEnvironment re = RuntimeEnvironmentBuilder.getDefault()
				.entityManagerFactory(emf)
				.userGroupCallback(userGroupCallback)
				.knowledgeBase(getKieBase(releaseId))
				.persistence(true)
				.get();
		return re;
	}
	
	protected RuntimeManager getRuntimeManager(KieReleaseId releaseId) throws SessionException {
		if(!runtimeManagers.containsKey(releaseId)) {
			RuntimeEnvironment re = getRuntimeEnvironment(releaseId);
			runtimeManagers.put(releaseId, RuntimeManagerFactory.Factory.get().newPerProcessInstanceRuntimeManager(re, releaseId.toString()));
		}
		
		return runtimeManagers.get(releaseId);
	}
	
	public RuntimeEngine getRuntimeEngine(KieReleaseId releaseId) throws SessionException {
		RuntimeManager rm = getRuntimeManager(releaseId);
		
		return rm.getRuntimeEngine(ProcessInstanceIdContext.get());
	}
	
	public RuntimeEngine getRuntimeEngineByProcessId(Long processInstanceId) {
		LOG.info("Loading instance: "+processInstanceId);
		ProcessInstanceIdContext context = ProcessInstanceIdContext.get(processInstanceId);
		
		LOG.info("Context: "+context);
		
		KieReleaseId releaseId = getReleaseIdByProcessId(processInstanceId);
		if(releaseId == null) {
			throw new InactiveProcessInstance(processInstanceId);
		}

		LOG.info("Kie Release: "+releaseId);
		
		
		RuntimeManager manager = getRuntimeManager(releaseId);
		
		return manager.getRuntimeEngine(context);
	}

	public RuntimeEngine getRuntimeEngineByTaskId(Long taskId) {
		Long processInstanceId = getProcessInstanceIdByTaskId(taskId);
		return this.getRuntimeEngineByProcessId(processInstanceId);
	}
	
	public RuntimeEngine getRuntimeEngineByWorkItemId(Long workItemId) {
		Long processInstanceId = getProcessInstanceIdByWorkItemId(workItemId);
		return this.getRuntimeEngineByProcessId(processInstanceId);
	}
	
	public Long getProcessInstanceIdByTaskId(Long taskId) {
		Task task = taskService.getTaskById(taskId);
		Long processInstanceId = task.getTaskData().getProcessInstanceId();
		
		return processInstanceId;
	}
	
	public Long getProcessInstanceIdByWorkItemId(Long workItemId) {
		Query q = em.createQuery("select processInstanceId from WorkItemInfo wii where kb.workItemId=:workItemId");
		q.setParameter("workItemId", workItemId);
		Long processInstanceId = (Long)q.getSingleResult();
		
		return processInstanceId;
	}

	public KieReleaseId getReleaseIdByProcessId(Long processInstanceId) {
		Query q = em.createQuery("from KieBaseXProcessInstance kb where kb.kieProcessInstanceId=:processInstanceId");
		q.setParameter("processInstanceId", processInstanceId);
		
		try {
			KieBaseXProcessInstance kb = (KieBaseXProcessInstance)q.getSingleResult();
			return new KieReleaseId(kb.getReleaseGroupId(), kb.getReleaseArtifactId(), kb.getReleaseVersion());
		}
		catch(NoResultException e) {
			return null;
		}
	}
	
	public KieReleaseId getReleaseIdByTaskId(Long taskId) {
		Task task = taskService.getTaskById(taskId);
		long processInstanceId = task.getTaskData().getProcessInstanceId();
		
		return this.getReleaseIdByProcessId(processInstanceId);
	}
	
	public KieReleaseId getReleaseIdByWorkItemId(Long workItemId) {
		Query q = em.createQuery("select processInstanceId from WorkItemInfo wii where kb.workItemId=:workItemId");
		q.setParameter("workItemId", workItemId);
		Long processInstanceId = (Long)q.getSingleResult();
		
		return this.getReleaseIdByProcessId(processInstanceId);
	}
	
	
	private static void setDefaultingProperty(String name, String val, PropertiesConfiguration config) {
		if(val == null) return;

		LOG.debug("Setting property: "+name+" to value: "+val);
		config.setProperty(name, val);
	}
	private static void setDefaultingProperty(String name, Boolean val, PropertiesConfiguration config) {
		if(val == null) return;

		String value = Boolean.toString(val);
		LOG.debug("Setting property: "+name+" to value: "+value);
		config.setProperty(name, value);
	}
	private static void setDefaultingProperty(String name, Integer val, PropertiesConfiguration config) {
		if(val == null) return;

		String value = Integer.toString(val);
		LOG.debug("Setting property: "+name+" to value: "+value);
		config.setProperty(name, value);
	}
	
	
	
	
	
}
