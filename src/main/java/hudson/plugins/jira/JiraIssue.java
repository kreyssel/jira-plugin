package hudson.plugins.jira;

import org.apache.commons.lang.builder.ToStringBuilder;
import org.apache.commons.lang.builder.ToStringStyle;

import hudson.plugins.jira.soap.RemoteIssue;

/**
 * One JIRA issue.
 *
 * <p>
 * This class is used to persist crucial issue information
 * so that Jenkins can display it without talking to JIRA.
 *
 * @author Kohsuke Kawaguchi
 * @see JiraSite#getUrl(JiraIssue) 
 */
public final class JiraIssue implements Comparable<JiraIssue> {
    /**
     * JIRA ID, like "MNG-1235".
     */
    public final String id;

    /**
     * Title of the issue.
     * For example, in case of MNG-1235, this is "NPE In DiagnosisUtils while using tomcat plugin"
     */
    public final String title;

    public final String status;
    
    public final String resolution;
    
    public final String action;
    
    public final String actionHint;
    
    public JiraIssue(String id, String title) {
        this.id = id;
        this.title = title;
        this.status = null;
        this.resolution = null;
        this.action = null;
        this.actionHint = null;
    }

    public JiraIssue(RemoteIssue issue, String status, String resolution, String action, String actionHint) {
        this.id = issue.getKey();
        this.title =  issue.getSummary();
        this.status = status;
        this.resolution = resolution;
        this.action = action;
        this.actionHint = actionHint;
    }

    public int compareTo(JiraIssue that) {
        return this.id.compareTo(that.id);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((id == null) ? 0 : id.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        JiraIssue other = (JiraIssue) obj;
        if (id == null) {
            if (other.id != null)
                return false;
        } else if (!id.equals(other.id))
            return false;
        return true;
    }
    
    @Override
    public String toString() {
       	return ToStringBuilder.reflectionToString(this, ToStringStyle.SIMPLE_STYLE);
    }
}
