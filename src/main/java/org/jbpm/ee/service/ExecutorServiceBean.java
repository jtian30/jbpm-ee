package org.jbpm.ee.service;

import javax.inject.Inject;

import org.jbpm.ee.service.remote.ExecutorServiceRemote;
import org.kie.api.command.Command;
import org.kie.api.runtime.CommandExecutor;

public class ExecutorServiceBean implements ExecutorServiceRemote {

	@Inject
	private RuntimeServiceBean runtimeService;
	
	private CommandExecutor commandExecutorDelegate;
	
	private void delegateCheck() {
		if (commandExecutorDelegate == null) {
			commandExecutorDelegate = runtimeService.getKnowledgeSession();
		}
	}
	
	@Override
	public <T> T execute(Command<T> command) {
		// TODO Auto-generated method stub
		return null;
	}

}
