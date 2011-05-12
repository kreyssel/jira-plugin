package hudson.plugins.jira;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.builder.EqualsBuilder;
import org.apache.commons.lang.builder.HashCodeBuilder;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

public class JiraWorkflowAction {

	final static Pattern ENTRY_PATTERN = Pattern.compile("^([a-z]+)[=](.*)$");
	
	// the commit action (resolve, close ...)
	public final String action;
	
	// the mapped jira worflow action ids
	public final List<String> actionIds;
	
	public JiraWorkflowAction(String configLineEntry)throws ParseException {
		String line = StringUtils.strip(configLineEntry);
		
		if(line == null) {
			throw new ParseException("[NULL]", 0);
		}
		
		Matcher m = ENTRY_PATTERN.matcher(line);
		if(!m.find() || m.groupCount() != 2) {
			throw new ParseException(line, 0);
		}		
		
		this.action = StringUtils.stripToNull(m.group(1));
		
		if( this.action == null ){
			throw new ParseException(line, 0);
		}
		
		String[] splitedIds = StringUtils.split(m.group(2), ',');
		Set<String> ids = new LinkedHashSet<String>(splitedIds.length); 
		
		for(String splitedId : splitedIds) {
			String id = StringUtils.stripToNull(splitedId);
			if(id == null) {
				throw new ParseException(configLineEntry, 0);
			}
			ids.add(id);
		}
		
		if(ids.isEmpty()) {
			throw new ParseException(configLineEntry, 0);
		}
		
		this.actionIds = Lists.newArrayList(ids);
	}
	
	public JiraWorkflowAction(String action, List<String> actionIds){
		this.action = action;
		this.actionIds = new ArrayList<String>(actionIds);
	}
	
	public static List<JiraWorkflowAction> parse(String multiLineConfig) throws ParseException {
		List<JiraWorkflowAction> mapping = new ArrayList<JiraWorkflowAction>();
		String[] lines = StringUtils.split(multiLineConfig, '\n');
		for(String line:lines){
			if(StringUtils.isBlank(line)) {
				continue;
			}
			JiraWorkflowAction actionMapping = new JiraWorkflowAction(line);
			mapping.add(actionMapping);
		}
		return mapping;
	}
	
	@Override
	public int hashCode() {
		return new HashCodeBuilder().append(action).toHashCode();
	}
	
	@Override
	public boolean equals(Object obj) {
		if(obj==null){
			return false;
		}
		if(obj instanceof JiraWorkflowAction == false){
			return false;
		}
		JiraWorkflowAction comp = (JiraWorkflowAction)obj;
		return new EqualsBuilder().append(this.action, comp.action).isEquals();
	}
}
