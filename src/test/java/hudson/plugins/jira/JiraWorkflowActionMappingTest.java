package hudson.plugins.jira;

import static org.junit.Assert.*;

import java.text.ParseException;
import java.util.Arrays;

import org.junit.Test;

public class JiraWorkflowActionMappingTest {

	@Test
	public void testMappingPattern() throws Exception {
		JiraWorkflowActionMapping a1 = new JiraWorkflowActionMapping("close=1, 2, 3");
		assertEquals("close", a1.action);
		assertEquals(Arrays.asList("1","2","3"), a1.actionIds);
	}
	
	@Test
	public void testMappingPatternParseErrors() throws Exception {
		assertParseError(null);
		assertParseError("");
		assertParseError("1,2,3");
		assertParseError("close 1,2,3");
		assertParseError("cl ose =1,2,3");
		assertParseError("=1,2,3");
		assertParseError("close = ");
		assertParseError("c lose =1,2");
	}
	
	private void assertParseError(String line){
		try {
			new JiraWorkflowActionMapping(line);
			fail(line);
		} catch( ParseException  ex ) {
			// OK
		}
	}
}

