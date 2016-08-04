package cucumber.runtime;

import cucumber.api.PendingException;
import cucumber.api.Result;
import cucumber.api.Scenario;
import cucumber.api.event.TestRunFinished;
import cucumber.api.formatter.Formatter;
import cucumber.runtime.formatter.PickleStepMatcher;
import cucumber.runtime.io.ClasspathResourceLoader;
import cucumber.runtime.model.CucumberFeature;
import gherkin.AstBuilder;
import gherkin.GherkinDialect;
import gherkin.Parser;
import gherkin.TokenMatcher;
import gherkin.ast.GherkinDocument;
import gherkin.pickles.PickleStep;
import gherkin.pickles.PickleTag;
import junit.framework.AssertionFailedError;
import org.junit.Ignore;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Arrays.asList;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyCollectionOf;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Ignore
public class TestHelper {
    private TestHelper() {
    }

    public static CucumberFeature feature(final String path, final String source) throws IOException {
        Parser<GherkinDocument> parser = new Parser<GherkinDocument>(new AstBuilder());
        TokenMatcher matcher = new TokenMatcher();

        GherkinDocument gherkinDocument = parser.parse(source, matcher);
        return new CucumberFeature(gherkinDocument, path);
    }

    public static Result result(String status) {
        if (status.equals(Result.FAILED)) {
            return result(status, mockAssertionFailedError());
        } else if (status.equals("pending")){
            return result(status, new PendingException());
        } else {
            return new Result(status, 0L, null);
        }
    }

    public static Result result(String status, Throwable error) {
        return new Result(status, 0L, error, null);
    }

    public static void runFeatureWithFormatter(final CucumberFeature feature, final Map<String, Result> stepsToResult, final List<SimpleEntry<String, Result>> hooks,
            final long stepHookDuration, final Formatter formatter) throws Throwable, FileNotFoundException {
        runFeaturesWithFormatter(Arrays.asList(feature), stepsToResult, Collections.<String,String>emptyMap(), hooks, stepHookDuration, formatter);
    }

    public static void runFeaturesWithFormatter(final List<CucumberFeature> features, final Map<String, Result> stepsToResult,
            final List<SimpleEntry<String, Result>> hooks, final long stepHookDuration, final Formatter formatter) throws Throwable {
        runFeaturesWithFormatter(features, stepsToResult, Collections.<String,String>emptyMap(), hooks, stepHookDuration, formatter);
    }

    public static void runFeatureWithFormatter(final CucumberFeature feature, final Map<String, String> stepsToLocation,
                                               final Formatter formatter) throws Throwable {
        runFeaturesWithFormatter(Arrays.asList(feature), Collections.<String, Result>emptyMap(), stepsToLocation,
                Collections.<SimpleEntry<String, Result>>emptyList(), 0L, formatter);
    }

    private static void runFeaturesWithFormatter(final List<CucumberFeature> features, final Map<String, Result> stepsToResult, final Map<String, String> stepsToLocation,
                                                  final List<SimpleEntry<String, Result>> hooks, final long stepHookDuration, final Formatter formatter) throws Throwable {
        final RuntimeOptions runtimeOptions = new RuntimeOptions("-p null");
        final ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        final ClasspathResourceLoader resourceLoader = new ClasspathResourceLoader(classLoader);
        final RuntimeGlue glue = createMockedRuntimeGlueThatMatchesTheSteps(stepsToResult, stepsToLocation, hooks);
        final Runtime runtime = new Runtime(resourceLoader, classLoader, asList(mock(Backend.class)), runtimeOptions, new StopWatch.Stub(stepHookDuration), glue);

        formatter.setEventPublisher(runtime.getEventBus());
        for (CucumberFeature feature : features) {
            runtime.runFeature(feature);
        }
        runtime.getEventBus().send(new TestRunFinished());
    }

    private static RuntimeGlue createMockedRuntimeGlueThatMatchesTheSteps(Map<String, Result> stepsToResult, Map<String, String> stepsToLocation,
                                                                          final List<SimpleEntry<String, Result>> hooks) throws Throwable {
        RuntimeGlue glue = mock(RuntimeGlue.class);
        TestHelper.mockSteps(glue, stepsToResult, stepsToLocation);
        TestHelper.mockHooks(glue, hooks);
        return glue;
    }

    private static void mockSteps(RuntimeGlue glue, Map<String, Result> stepsToResult, Map<String, String> stepsToLocation) throws Throwable {
        for (String stepText : mergeStepSets(stepsToResult, stepsToLocation)) {
            Result stepResult = getResultWithDefaultPassed(stepsToResult, stepText);
            if (!"undefined".equals(stepResult.getStatus())) {
                StepDefinitionMatch matchStep = mock(StepDefinitionMatch.class);
                when(matchStep.getMatch()).thenReturn(matchStep);
                when(glue.stepDefinitionMatch(anyString(), TestHelper.stepWithName(stepText), (GherkinDialect) any())).thenReturn(matchStep);
                mockStepText(matchStep, stepText);
                mockStepResult(stepResult, matchStep);
                mockStepLocation(getLocationWithDefaultEmptyString(stepsToLocation, stepText), matchStep);
            }
        }
    }

    private static void mockStepText(StepDefinitionMatch matchStep, String stepText) {
        when(matchStep.getStepText()).thenReturn(stepText);
    }

    private static void mockStepResult(Result stepResult, StepDefinitionMatch matchStep) throws Throwable {
        if ("pending".equals(stepResult.getStatus())) {
            doThrow(new PendingException()).when(matchStep).runStep((GherkinDialect) any(), (Scenario) any());
        } else if (Result.FAILED.equals(stepResult.getStatus())) {
            doThrow(stepResult.getError()).when(matchStep).runStep((GherkinDialect) any(), (Scenario) any());
        } else if (!Result.PASSED.equals(stepResult.getStatus()) &&
                   !"skipped".equals(stepResult.getStatus())) {
            fail("Cannot mock step to the result: " + stepResult.getStatus());
        }
    }

    private static void mockStepLocation(String stepLocation, StepDefinitionMatch matchStep) {
        when(matchStep.getLocation()).thenReturn(stepLocation);
    }

    private static void mockHooks(RuntimeGlue glue, final List<SimpleEntry<String, Result>> hooks) throws Throwable {
        List<HookDefinition> beforeHooks = new ArrayList<HookDefinition>();
        List<HookDefinition> afterHooks = new ArrayList<HookDefinition>();
        for (SimpleEntry<String, Result> hookEntry : hooks) {
            TestHelper.mockHook(hookEntry, beforeHooks, afterHooks);
        }
        if (!beforeHooks.isEmpty()) {
            when(glue.getBeforeHooks()).thenReturn(beforeHooks);
        }
        if (!afterHooks.isEmpty()) {
            when(glue.getAfterHooks()).thenReturn(afterHooks);
        }
    }

    private static void mockHook(SimpleEntry<String, Result> hookEntry, List<HookDefinition> beforeHooks,
                                 List<HookDefinition> afterHooks) throws Throwable {
        HookDefinition hook = mock(HookDefinition.class);
        when(hook.matches(anyCollectionOf(PickleTag.class))).thenReturn(true);
        if (hookEntry.getValue().getStatus().equals("failed")) {
            doThrow(hookEntry.getValue().getError()).when(hook).execute((cucumber.api.Scenario) any());
        } else if (hookEntry.getValue().getStatus().equals("pending")) {
            doThrow(new PendingException()).when(hook).execute((cucumber.api.Scenario) any());
        }
        if ("before".equals(hookEntry.getKey())) {
            beforeHooks.add(hook);
        } else if ("after".equals(hookEntry.getKey())) {
            afterHooks.add(hook);
        } else {
            fail("Only before and after hooks are allowed, hook type found was: " + hookEntry.getKey());
        }
    }

    private static PickleStep stepWithName(String name) {
        return argThat(new PickleStepMatcher(name));
    }

    private static AssertionFailedError mockAssertionFailedError() {
        AssertionFailedError error = mock(AssertionFailedError.class);
        Answer<Object> printStackTraceHandler = new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {
                PrintWriter writer = (PrintWriter) invocation.getArguments()[0];
                writer.print("the stack trace");
                return null;
            }
        };
        doAnswer(printStackTraceHandler).when(error).printStackTrace((PrintWriter) any());
        return error;
    }

    public static SimpleEntry<String, Result> hookEntry(String type, Result result) {
        return new SimpleEntry<String, Result>(type, result);
    }

    private static Set<String> mergeStepSets(Map<String, Result> stepsToResult, Map<String, String> stepsToLocation) {
        Set<String> steps = new HashSet<String>(stepsToResult.keySet());
        steps.addAll(stepsToLocation.keySet());
        return steps;
    }

    private static Result getResultWithDefaultPassed(Map<String, Result> stepsToResult, String step) {
        return stepsToResult.containsKey(step) ? stepsToResult.get(step) : new Result(Result.PASSED, 0L, null);
    }

    private static String getLocationWithDefaultEmptyString(Map<String, String> stepsToLocation, String step) {
        return stepsToLocation.containsKey(step) ? stepsToLocation.get(step) : "";
    }
}
