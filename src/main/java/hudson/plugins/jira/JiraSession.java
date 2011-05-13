package hudson.plugins.jira;

import hudson.plugins.jira.soap.JiraSoapService;
import hudson.plugins.jira.soap.RemoteAuthenticationException;
import hudson.plugins.jira.soap.RemoteComment;
import hudson.plugins.jira.soap.RemoteGroup;
import hudson.plugins.jira.soap.RemoteIssue;
import hudson.plugins.jira.soap.RemoteNamedObject;
import hudson.plugins.jira.soap.RemotePermissionException;
import hudson.plugins.jira.soap.RemoteProject;
import hudson.plugins.jira.soap.RemoteProjectRole;
import hudson.plugins.jira.soap.RemoteResolution;
import hudson.plugins.jira.soap.RemoteStatus;
import hudson.plugins.jira.soap.RemoteValidationException;

import java.rmi.RemoteException;
import java.text.ParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.logging.Logger;

import org.apache.commons.lang.StringUtils;

/**
 * Connection to JIRA.
 * 
 * <p>
 * JIRA has a built-in timeout for a session, so after some inactive period the
 * session will become invalid. The caller must make sure that this doesn't
 * happen.
 * 
 * @author Kohsuke Kawaguchi
 */
public class JiraSession {
	public final JiraSoapService service;

	/**
	 * This security token is used by the server to associate SOAP invocations
	 * with a specific user.
	 */
	public final String token;

	/**
	 * Lazily computed list of project keys.
	 */
	private Set<String> projectKeys;

	/**
	 * Lazily computed list of satuses with description 
	 * Map<id, description>
	 * TODO: invalidate this map after ??? hours
	 */
	Map<String, String> statuses;
	
	/**
	 * Lazily computed list of resolutions with description 
	 * Map<id, description>
	 * TODO: invalidate this map after ??? hours
	 */
	Map<String, String> resolutions;
	
	/**
	 * This session is created for this site.
	 */
	private final JiraSite site;

	/* package */JiraSession(JiraSite site, JiraSoapService service,
			String token) {
		this.service = service;
		this.token = token;
		this.site = site;
	}

	/**
	 * Returns the set of project keys (like MNG, JENKINS, etc) that are
	 * available in this JIRA.
	 * 
	 * Guarantees to return all project keys in upper case.
	 */
	public Set<String> getProjectKeys() throws RemoteException {
		if (projectKeys == null) {
			LOGGER.fine("Fetching remote project key list from "
					+ site.getName());
			RemoteProject[] remoteProjects = service
					.getProjectsNoSchemes(token);
			projectKeys = new HashSet<String>(remoteProjects.length);
			for (RemoteProject p : remoteProjects) {
				projectKeys.add(p.getKey().toUpperCase());
			}
			LOGGER.fine("Project list=" + projectKeys);
		}
		return projectKeys;
	}

	/**
	 * Adds a comment to the existing issue. Constrains the visibility of the
	 * comment the the supplied groupVisibility.
	 * 
	 * @param groupVisibility
	 */
	public void addComment(String issueId, String comment,
			String groupVisibility, String roleVisibility) throws RemoteException {
		RemoteComment rc = new RemoteComment();
		rc.setBody(comment);

		try {
			if (roleVisibility != null && roleVisibility.equals("") == false
					&& getRole(roleVisibility) != null) {
				rc.setRoleLevel(roleVisibility);
			}
		} catch (RemoteValidationException rve) {
			LOGGER.throwing(this.getClass().toString(), "setRoleLevel", rve);
		}
		
		try {
			if (groupVisibility != null && groupVisibility.equals("") == false
					&& getGroup(groupVisibility) != null) {
				rc.setGroupLevel(groupVisibility);
			}
		} catch (RemoteValidationException rve) {
			LOGGER.throwing(this.getClass().toString(), "setGroupLevel", rve);
		}
		
		service.addComment(token, issueId, rc);
	}

	public void progressWorkflowAction(String issueId, String commitAction) throws RemoteException, ParseException {

		// find the mapped jira workflow action ids for the issues commit action
		JiraWorkflowActionMapping action=null;
		
		for(JiraWorkflowActionMapping actionMapping : site.getWorkflowActionMappings()){
			if(StringUtils.equalsIgnoreCase(commitAction, actionMapping.action)){
				action = actionMapping;
				break;
			}
		}
		
		if(action==null) {
			LOGGER.severe("Could not find action mapping for action " + commitAction + " - action executed for issue " + issueId);
			// TODO: send error email
			return;
		}
		
		// find the first mapped available action for the issue
		String actionId = null; 
		
		RemoteNamedObject[] availableActions = service.getAvailableActions(token, issueId);
		if(availableActions!=null && availableActions.length > 0) {
			for(RemoteNamedObject availableAction : availableActions ){
				for(String mappedActionId: action.actionIds){
					if(StringUtils.equals(availableAction.getId(), mappedActionId)){
						actionId = mappedActionId;
						break;
					}
				}
			}
		}
		
		if(actionId == null) {
			StringBuilder b = new StringBuilder();
			if(availableActions != null && availableActions.length > 0) {
				b.append("\nAvailable jira worfklow actions are: ");
				for(RemoteNamedObject availableAction : availableActions ){
					b.append("\n\t");
					b.append(availableAction.getName());
					b.append(" = ID ");
					b.append(availableAction.getId());
				}
			} else {
				b.append("Could not found any actions for the issue!");
			}
			
			LOGGER.severe("Could not find action or user have no rights to access action '#"+action.action + "' for issue "+ issueId + b);
			// TODO: send error email
			// TODO: should build fail?
			return;
		}
		
		// this executes the jira workflow action and returns the changed issue
		// TODO: add handling for workflow action fields
		RemoteIssue changedIssue = service.progressWorkflowAction(token, issueId, actionId, null);
	}
	
	/**
	 * Gets the details of one issue.
	 * 
	 * @param id
	 *            Issue ID like "MNG-1235".
	 * @return null if no such issue exists.
	 */
	public RemoteIssue getIssue(String id) throws RemoteException {
		if (existsIssue(id))
			return service.getIssue(token, id);
		else
			return null;
	}

	public String getStatus(String id) throws RemotePermissionException, RemoteAuthenticationException, RemoteException {
		if(StringUtils.isBlank(id)) {
			return null;
		}
		
		if(this.statuses != null && this.statuses.containsKey(id)) {
			return this.statuses.get(id);
		}
		
		RemoteStatus[] remoteStatuses = service.getStatuses(this.token);
		this.statuses = new HashMap<String, String>(remoteStatuses.length);
		
		for(RemoteStatus remoteStatus : remoteStatuses) {
			this.statuses.put(remoteStatus.getId(), remoteStatus.getDescription());
		}		
		
		return this.statuses.get(id);
	}
	
	public String getResolution(String id) throws RemotePermissionException, RemoteAuthenticationException, RemoteException {
		if(StringUtils.isBlank(id)) {
			return null;
		}		
		
		if(this.resolutions != null && this.resolutions.containsKey(id)) {
			return this.resolutions.get(id);
		}
		
		RemoteResolution[] remoteResolutions = service.getResolutions(this.token);
		this.resolutions = new HashMap<String, String>(remoteResolutions.length);
		
		for(RemoteResolution remoteResolution : remoteResolutions) {
			this.resolutions.put(remoteResolution.getId(), remoteResolution.getDescription());
		}		
		
		return this.resolutions.get(id);
	}
	
	/**
	 * Gets the details of a group, given a groupId. Used for validating group
	 * visibility.
	 * 
	 * @param Group
	 *            ID like "Software Development"
	 * @return null if no such group exists
	 */
	public RemoteGroup getGroup(String groupId) throws RemoteException {
		LOGGER.fine("Fetching groupInfo from " + groupId);
		return service.getGroup(token, groupId);
	}
	
	/**
	 * Gets the details of a role, given a roleId. Used for validating role
	 * visibility.
	 * 
	 * TODO: Cannot validate against the real project role the user have in the project, 
	 * jira soap api has no such function!
	 * 
	 * @param Role
	 *            ID like "Software Development"
	 * @return null if no such role exists
	 */
	public RemoteProjectRole getRole(String roleId) throws RemoteException {
		LOGGER.fine("Fetching roleInfo from " + roleId);
		
		RemoteProjectRole[] roles= service.getProjectRoles(token);
		
		if(roles != null && roles.length > 0) {
			for(RemoteProjectRole role : roles) {
				if(role != null && role.getName() != null && role.getName().equals(roleId)) {
					return role;
				}
			}
		}
		
		LOGGER.info("Did not find role named " + roleId + ".");

		return null;
	}

	public boolean existsIssue(String id) throws RemoteException {
		return site.existsIssue(id);
	}

	private static final Logger LOGGER = Logger.getLogger(JiraSession.class
			.getName());
}
