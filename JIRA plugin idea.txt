track back support. hudson should support track backs
  http://www.atlassian.com/software/jira/docs/v3.7.4/trackback.html
  needs to find out how it works.

use listeners to monitor comments that have links?
  IssueEventListener
  http://www.atlassian.com/software/jira/docs/v3.7.4/listeners.html

SOAP-based remote access (done)
  http://confluence.atlassian.com/display/JIRA/Creating+a+SOAP+Client
  http://localhost:90/rpc/soap/jirasoapservice-v2?wsdl

perhaps add a tab to issue, listing related builds?
  see how CVS integration does it:
  http://www.atlassian.com/software/jira/docs/v3.7.4/images/docs/config/cvs_integration-versioncontrol.png
  or see how Subversion integration works:
  http://svn.atlassian.com/svn/public/contrib/jira/subversion-jira-plugin/trunk/
  https://issues.apache.org/jira/browse/IO-113?page=com.atlassian.jira.plugin.ext.subversion:subversion-commits-tabpanel

JIRA Wiki formatting rules:
  http://localhost:90/secure/WikiRendererHelpAction.jspa?section=texteffects

Execute Workflow Actions via Commit Comment

  Idea: resolve/close jira issues with a commit comment
  
  JIRA Facts:
  
  - a jira worflow defines how you can process an issue
  - you can have multiple separated worflows in you jira installation
  	http://www.atlassian.com/software/jira/tour/workflow.jsp
  - a issue has a binding to one workflow
  - workflow steps has one or more workflow actions for a transition to another 
  	workflow step
  - workflow actions can have role bindings
  - every workflow action has a unique id
  - workflow actions can have binded "actions fields" that be shown as a 
  	subscreen if you call the action
  - this action fields can have validators, if a validator mark a field as error, 
  	the transition to the next workflow step cannot be reached 
  - possible format for commit comment actions
    http://confluence.atlassian.com/display/JIRASTUDIO/Actioning+Issues+via+Commit+Messages
    
  - JENKINS Commit Action Format:
    [JIRA-ID] #[action] [resolution] comment
    
    Example:
    FOO-123 #resolve fixed The issue is now fixed
    
    Example with multiple issues in one comment:
    FOO-123 #resolve fixed The issue is now fixed BAR-234 #reopen resolution The issue must now be reopen!
    
  - We need a mapping of Jenkins Commit Jira Actions to one or more Jira Worflow Actions.
  	
  	Format:
  	[Jeankins Commit Jira Workflow Action]=[[Jira Workflow ID], ...]

    You can find the IDs for the jira workflow action in the jira admin workflow 
    steps panel (the number within brackets).
  	
  	Example of a mapping for two workflows:
  	
  		JIRA Workflow "With QA":
  		
  			Workflow Action | ID
  			================+====
  			Start progress  | 1
  			Stop progress   | 2
  			Reopen          | 3
  			Resolve         | 4
  			Confirm QA      | 5
  			Close           | 6
  			
		JIRA Workflow "Without QA and Reopen": 
		
  			Workflow Action | ID
  			================+====
  			Start progress  | 10
  			Stop progress   | 11
  			Resolve         | 12
  			Close           | 13
  	
	  	Mapping:
	  	
			start=1,10
			stop=2,11
			reopen=3
			resolve=4,12
			qa=5
			close=6,13
    
    If we have multiple workflow actions for one commit action, we can find 
    out which action is possible for the issue. This means if you have multiple
    separated workflows you can map the different workflow actions to the same 
    commit action.
        
  - TODO: For non trivial workflows with required action fields, how can i provide the values for it?
  
  - In out favorite scm client, can we use commit comment templates to keep the notation.
  
  - Error handling: 
    If any error occurred (workflow action not found, error on execute the action 
    because required fields ..), we send a email to the committer with the error.
