package hudson.plugins.jira;

import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Hudson;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.AbstractBuild.DependencyChange;
import hudson.plugins.jira.soap.RemoteIssue;
import hudson.plugins.jira.soap.RemotePermissionException;
import hudson.scm.RepositoryBrowser;
import hudson.scm.ChangeLogSet.AffectedFile;
import hudson.scm.ChangeLogSet.Entry;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.rmi.RemoteException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.rpc.ServiceException;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.builder.EqualsBuilder;
import org.apache.commons.lang.builder.HashCodeBuilder;

/**
 * Actual JIRA update logic.
 *
 * 
 * @author Kohsuke Kawaguchi
 */
class Updater {
    static boolean perform(AbstractBuild<?, ?> build, BuildListener listener) throws InterruptedException, IOException {
        PrintStream logger = listener.getLogger();
        List<JiraIssue> issues = null;
        
        try {
            JiraSite site = JiraSite.get(build.getProject());
            if(site==null) {
                logger.println(Messages.Updater_NoJiraSite());
                build.setResult(Result.FAILURE);
                return true;
            }
    
            String rootUrl = Hudson.getInstance().getRootUrl();
            if(rootUrl==null) {
                logger.println(Messages.Updater_NoJenkinsUrl());
                build.setResult(Result.FAILURE);
                return true;
            }
            
            Set<ParsedIssueDetails> ids = findIssueIdsRecursive(build, site.getIssuePattern(), listener);
    
            if(ids.isEmpty()) {
                if(debug)
                    logger.println("No JIRA issues found.");
                return true;    // nothing found here.
            }
            
            JiraSession session = null;
    		try {
    			session = site.createSession();
    		} catch (ServiceException e) {
    			listener.getLogger().println(Messages.Updater_FailedToConnect());
                e.printStackTrace(listener.getLogger());
    		}
            if(session==null) {
                logger.println(Messages.Updater_NoRemoteAccess());
                build.setResult(Result.FAILURE);
                return true;
            }
    
            issues = getJiraIssues(ids, session, logger);
            build.getActions().add(new JiraBuildAction(build,issues));
            
            boolean doSubmitComments = false;
            if (site.updateJiraIssueForAllStatus){
                doSubmitComments = true;
            } else {
              doSubmitComments = build.getResult().isBetterOrEqualTo(Result.UNSTABLE);
            }
            boolean useWikiStyleComments = site.supportsWikiStyleComment;
            
            if (doSubmitComments) {
                submitComments(build, logger, rootUrl, issues,
                        session, useWikiStyleComments, site.recordScmChanges, site.groupVisibility, site.roleVisibility);
            } else {
                // this build didn't work, so carry forward the issues to the next build
                build.addAction(new JiraCarryOverAction(issues));
            }
            
            boolean doExecuteWorkflowActions = false;
            if (site.executeJiraWorflowActionForAllStatus){
            	doExecuteWorkflowActions = true;
            } else {
            	doExecuteWorkflowActions = build.getResult().isBetterOrEqualTo(Result.UNSTABLE);
            }
            
            if (doExecuteWorkflowActions) {
                executeWorflowAction(build, logger, issues, session);
            } else {
                // this build didn't work, so carry forward the issues to the next build
                build.addAction(new JiraCarryOverAction(issues));
            }
            
        } catch (Exception e) {
            logger.println("Error updating JIRA issues. Saving issues for next build.\n" + e);
            if (issues != null && !issues.isEmpty()) {
                // updating issues failed, so carry forward issues to the next build
                build.addAction(new JiraCarryOverAction(issues));
            }
        }

        return true;
    }

 
    /**
     * Submits comments for the given issues.
     * Removes from <code>issues</code> the ones which appear to be invalid.
     * @param build
     * @param logger
     * @param jenkinsRootUrl
     * @param issues
     * @param session
     * @param useWikiStyleComments
     * @param recordScmChanges
     * @param groupVisibility
     * @throws RemoteException
     */
    static void submitComments(
	            AbstractBuild<?, ?> build, PrintStream logger, String jenkinsRootUrl,
	            List<JiraIssue> issues, JiraSession session,
	            boolean useWikiStyleComments, boolean recordScmChanges, String groupVisibility, String roleVisibility) throws RemoteException {
	    // copy to prevent ConcurrentModificationException
	    List<JiraIssue> copy = new ArrayList<JiraIssue>(issues);
        for (JiraIssue issue : copy) {
            try {
                logger.println(Messages.Updater_Updating(issue.id));
                StringBuilder aggregateComment = new StringBuilder();
                for(Entry e :build.getChangeSet()){
                    if(e.getMsg().toUpperCase().contains(issue.id)){
                        aggregateComment.append(e.getMsg()).append("\n");
                        // kutzi: don't know why the issue id was removed in previous versions:
                        //aggregateComment = aggregateComment.replaceAll(id, "");
                    }
                }

                session.addComment(issue.id,
                    createComment(build, useWikiStyleComments,
                            jenkinsRootUrl, aggregateComment.toString(), recordScmChanges, issue), groupVisibility, roleVisibility);
            } catch (RemotePermissionException e) {
                // Seems like RemotePermissionException can mean 'no permission' as well as
                // 'issue doesn't exist'.
                // To prevent carrying forward invalid issues forever, we have to drop them
                // even if the cause of the exception was different.
                logger.println("Looks like " + issue.id + " is no valid JIRA issue. Issue will not be updated or you dont have valid rights.\n" + e);
                issues.remove(issue);
            }
        }
    }

    static void executeWorflowAction(
            AbstractBuild<?, ?> build, PrintStream logger, List<JiraIssue> issues, JiraSession session) throws RemoteException {

	    for (JiraIssue issue : issues) {
	        try {
	        	if(StringUtils.isNotBlank(issue.action)) {
	        		session.progressWorkflowAction(issue.id, issue.action);
	        	}
	        } catch (RemotePermissionException e) {
	            logger.println("Looks like " + issue.id + " is no valid JIRA issue. Issue will not be updated or you dont have valid rights.\n" + e);
	        } catch (ParseException e) {
	            logger.println("Looks like an error in the jira worflow action mapping config - " + e.getMessage());
	        }
	    }
    }

    
	private static List<JiraIssue> getJiraIssues( 
            Set<ParsedIssueDetails> ids, JiraSession session, PrintStream logger) throws RemoteException {
        List<JiraIssue> issues = new ArrayList<JiraIssue>(ids.size());
        for (ParsedIssueDetails id : ids) {
            if(!session.existsIssue(id.id)) {
                if(debug)
                    logger.println(id.id+" looked like a JIRA issue but it wasn't");
                continue;   // token looked like a JIRA issue but it's actually not.
            }
            
            RemoteIssue issue = session.getIssue(id.id);
            String status = session.getStatus(issue.getStatus());
            String resolution = session.getResolution(issue.getResolution());
            
            issues.add(new JiraIssue(issue, status, resolution, id.action, id.comment));
        }
        return issues;
    }
	

    /**
     * Creates a comment to be used in JIRA for the build.
     */
    private static String createComment(AbstractBuild<?, ?> build,
            boolean wikiStyle, String jenkinsRootUrl, String scmComments, boolean recordScmChanges, JiraIssue jiraIssue) {
		String comment = String.format(
		    wikiStyle ?
		    "Integrated in !%1$simages/16x16/%3$s! [%2$s|%4$s]\n     %5$s":
		    "Integrated in %2$s (See [%4$s])\n    %5$s",
		    jenkinsRootUrl,
		    build,
		    build.getResult().color.getImage(),
		    Util.encode(jenkinsRootUrl+build.getUrl()),
		    scmComments);
		if (recordScmChanges) {
		    List<String> scmChanges = getScmComments(wikiStyle, build, jiraIssue );
		    StringBuilder sb = new StringBuilder(comment);
		    for (String scmChange : scmChanges)
		    {
		        sb.append( "\n" ).append( scmChange );
		    }
		    return sb.toString();
		}
		return comment;
	}
	
	private static List<String> getScmComments(boolean wikiStyle, AbstractBuild<?, ?> build, JiraIssue jiraIssue)
	{
	    RepositoryBrowser repoBrowser = null;
	    if (build.getProject().getScm() != null) {
	        repoBrowser = build.getProject().getScm().getEffectiveBrowser();
	    }
        List<String> scmChanges = new ArrayList<String>();
	    for (Entry change : build.getChangeSet()) {
	        if (jiraIssue != null  && !StringUtils.contains( change.getMsg(), jiraIssue.id )) {
	            continue;
	        }
	        try {
    	        String uid = change.getAuthor().getId();
    	        URL url = repoBrowser == null ? null : repoBrowser.getChangeSetLink( change );
    	        StringBuilder scmChange = new StringBuilder();
    	        if (StringUtils.isNotBlank( uid )) {
    	            scmChange.append( uid ).append( " : " );
    	        }
    	        if (url != null  && StringUtils.isNotBlank( url.toExternalForm() )) {
    	            if (wikiStyle) {
    	                String revision = getRevision( change );
    	                if (revision != null)
    	                {
    	                    scmChange.append( "[" ).append( revision );
    	                    scmChange.append( "|" );
    	                    scmChange.append( url.toExternalForm() ).append( "]" );
    	                }
    	                else
    	                {
    	                    scmChange.append( "[" ).append( url.toExternalForm() ).append( "]" );
    	                }
    	            } else {
    	                scmChange.append( url.toExternalForm() );
    	            }
    	        }
    	        scmChange.append( "\nFiles : " ).append( "\n" );
    	        // see http://issues.jenkins-ci.org/browse/JENKINS-2508
    	        //added additional try .. catch; getAffectedFiles is not supported by all SCM implementations
    	        try {
	    	        for (AffectedFile affectedFile : change.getAffectedFiles()) {
	    	            scmChange.append( "* " ).append( affectedFile.getPath() ).append( "\n" );
	    	        }
    	        } catch (UnsupportedOperationException e) {
    	            LOGGER.warning( "Unsupported SCM operation 'getAffectedFiles'. Fall back to getAffectedPaths.");
	    	        for (String affectedPath : change.getAffectedPaths()) {
	    	            scmChange.append( "* " ).append( affectedPath ).append( "\n" );
	    	        }
    	            
    	        }
    	        if (scmChange.length()>0) {
    	            scmChanges.add( scmChange.toString() );
    	        }
	        } catch (IOException e) {
	            LOGGER.warning( "skip failed to calculate scm repo browser link " + e.getMessage() );
	        }
	    }
	    return scmChanges;
	}
	
	private static String getRevision(Entry entry) {
	    // svn at least can get the revision
	    try {
	        Class<?> clazz = entry.getClass();
	        Method method = clazz.getMethod( "getRevision", (Class[])null );
	        if (method==null){
	            return null;
	        }
	        Object revObj = method.invoke( entry, (Object[])null );
	        return (revObj != null) ? revObj.toString() : null;
	    } catch (Exception e) {
	        return null;
	    }
	}
	

    /**
     * Finds the strings that match JIRA issue ID patterns.
     *
     * This method returns all likely candidates and doesn't check
     * if such ID actually exists or not. We don't want to use
     * {@link JiraSite#existsIssue(String)} here so that new projects
     * in JIRA can be detected.
     */
    private static Set<ParsedIssueDetails> findIssueIdsRecursive(AbstractBuild<?,?> build, Pattern pattern,
    		BuildListener listener) {
        Set<ParsedIssueDetails> ids = new HashSet<ParsedIssueDetails>();

        // first, issues that were carried forward.
        Run<?, ?> prev = build.getPreviousBuild();
        if(prev!=null) {
            JiraCarryOverAction a = prev.getAction(JiraCarryOverAction.class);
            if(a!=null) {
                //ids.addAll(a.getIDs());
            	// FIXME
            }
        }

        // then issues in this build
        findIssues(build,ids, pattern, listener);

        // check for issues fixed in dependencies
        for( DependencyChange depc : build.getDependencyChanges(build.getPreviousBuild()).values())
            for(AbstractBuild<?, ?> b : depc.getBuilds())
                findIssues(b,ids, pattern, listener);

        return ids;
    }

    /**
     * @param pattern pattern to use to match issue ids
     */    
    static void findIssues(AbstractBuild<?,?> build, Set<ParsedIssueDetails> ids, Pattern pattern,
    		BuildListener listener) {
        for (Entry change : build.getChangeSet()) {
        	String commitMessage = change.getMsg();

        	LOGGER.fine("Looking for JIRA ID in " + commitMessage);

            Matcher m = pattern.matcher(commitMessage);
            int idx = 0;            
            while (m.find(idx)) {
            	if (m.groupCount() >= 1) {
	                String issueId = StringUtils.upperCase(m.group(1));
	                idx = m.end();
	                String commentAfterIssueId = commitMessage.substring(idx, m.find(idx) ? m.start() : commitMessage.length());
	                ParsedIssueDetails parsedIssueDetails = new ParsedIssueDetails(issueId, commentAfterIssueId);
	                ids.add(parsedIssueDetails);
            	} else {
            		listener.getLogger().println("Warning: The JIRA pattern " + pattern + " doesn't define a capturing group!");
            	}
            }
        }
    }

    private static final Logger LOGGER = Logger.getLogger(Updater.class.getName());

    /**
     * Debug flag.
     */
    public static boolean debug = false;
    
    /**
     * A representation of a issue id + issue workflow action + optional action 
     * field value + optional comment
     * 
     * @author kreyssel
     */
    static final class ParsedIssueDetails implements Comparable<ParsedIssueDetails> {
    	
    	// commit actions starts with a sharp followed by optional action 
    	// field values and a optional comment
    	// Example: JIRA-123 #resolve fixed my comment
    	private final static Pattern ACTION_PATTERN = Pattern.compile("[#](\\w+)\\s?(.*)");
    	
    	// jira issue id in uppercase
    	final String id;
    	
    	// the commit action in lowercase (resolve, close, reopen, ...)
    	final String action;

    	// a additional comment
    	final String comment;
    	
    	ParsedIssueDetails(String id, String commentAfterIssueId ){
    		
    		this.id = StringUtils.upperCase(id);
    		
    		String trimmedComment = StringUtils.stripToEmpty(commentAfterIssueId);
    		
    		Matcher m = ACTION_PATTERN.matcher(trimmedComment);
    		
    		// commit actions must start with # followed by additional stuff
    		if (m.find()) {
    			this.action = m.groupCount() >= 1 ? StringUtils.lowerCase(StringUtils.stripToEmpty(m.group(1))) : "";
    			this.comment = m.groupCount() >= 2 ? StringUtils.stripToEmpty(m.group(2)) : "";
    		} else {
    			this.action = "";
    			this.comment = trimmedComment;
    		}
    	}
    	
    	public boolean hasAction() {
    		return StringUtils.isNotBlank(action);
    	}
    	
    	public int compareTo(ParsedIssueDetails comp) {
    		return this.id.compareTo(comp.id);
    	}
    	
    	@Override
    	public boolean equals(Object obj) {
    		if(obj == null)
    			return false;
    		
    		if(obj instanceof ParsedIssueDetails == false)
    			return false;
    			
    		return new EqualsBuilder().append(this.id, ((ParsedIssueDetails)obj).id).isEquals();
    	}
    	
    	@Override
    	public int hashCode() {
    		return new HashCodeBuilder().append(id).toHashCode();
    	}
    	
    	@Override
    	public String toString() {
    		return this.id;
    	}
    }

}
