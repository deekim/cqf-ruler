package org.opencds.cqf.dstu3.providers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import java.util.Map.Entry;

import org.opencds.cqf.cql.service.Response;
import org.opencds.cqf.cql.service.Service;
import org.opencds.cqf.cql.service.factory.DataProviderFactory;
import org.opencds.cqf.common.factories.DefaultTerminologyProviderFactory;
import org.opencds.cqf.cql.service.factory.TerminologyProviderFactory;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.cqframework.cql.cql2elm.CqlTranslator;
import org.cqframework.cql.cql2elm.CqlTranslatorException;
import org.cqframework.cql.elm.execution.VersionedIdentifier;
import org.cqframework.cql.elm.tracking.TrackBack;
import org.hl7.fhir.instance.model.api.IBaseResource;

import org.hl7.fhir.dstu3.model.ActivityDefinition;
import org.hl7.fhir.dstu3.model.Bundle;
import org.hl7.fhir.dstu3.model.DomainResource;
import org.hl7.fhir.dstu3.model.Endpoint;
import org.hl7.fhir.dstu3.model.Extension;
import org.hl7.fhir.dstu3.model.IdType;
import org.hl7.fhir.dstu3.model.Library;
import org.hl7.fhir.dstu3.model.Measure;
import org.hl7.fhir.dstu3.model.Parameters;
import org.hl7.fhir.dstu3.model.PlanDefinition;
import org.hl7.fhir.dstu3.model.Reference;
import org.hl7.fhir.dstu3.model.Resource;
import org.hl7.fhir.dstu3.model.StringType;
import org.hl7.fhir.dstu3.model.Type;
import org.opencds.cqf.common.evaluation.RulerLibraryLoader;
import org.opencds.cqf.dstu3.factories.DefaultLibraryLoaderFactory;
import org.opencds.cqf.common.helpers.ClientHelper;
import org.opencds.cqf.common.helpers.DateHelper;
import org.opencds.cqf.common.helpers.TranslatorHelper;
// import org.opencds.cqf.cql.runtime.DateTime;
// import org.opencds.cqf.cql.runtime.Interval;
import org.opencds.cqf.cql.engine.terminology.TerminologyProvider;
import org.opencds.cqf.dstu3.helpers.FhirMeasureBundler;
import org.opencds.cqf.dstu3.helpers.LibraryHelper;
import org.opencds.cqf.common.providers.LibraryResolutionProvider;

import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import org.opencds.cqf.common.helpers.UsingHelper;
import org.opencds.cqf.common.providers.LibraryResolutionProvider;
import org.opencds.cqf.cql.engine.data.DataProvider;
import org.opencds.cqf.cql.engine.execution.Context;
import org.opencds.cqf.cql.engine.runtime.DateTime;
import org.opencds.cqf.cql.engine.runtime.Interval;
//import org.opencds.cqf.cql.engine.terminology.TerminologyProvider;
import org.opencds.cqf.dstu3.helpers.FhirMeasureBundler;
import org.opencds.cqf.dstu3.helpers.LibraryHelper;

import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.context.FhirContext;

/**
 * Created by Bryn on 1/16/2017.
 */
public class CqlExecutionProvider {
    private DataProviderFactory dataProviderFactory;
    private LibraryResolutionProvider<org.hl7.fhir.dstu3.model.Library> libraryResourceProvider;
    private FhirContext fhirContext;
    private TerminologyProvider localSystemTerminologyProvider;

    public CqlExecutionProvider(LibraryResolutionProvider<org.hl7.fhir.dstu3.model.Library> libraryResourceProvider, DataProviderFactory dataProviderFactory, FhirContext fhirContext, TerminologyProvider localSystemTerminologyProvider) {
        this.dataProviderFactory = dataProviderFactory;
        this.libraryResourceProvider = libraryResourceProvider;
        this.fhirContext = fhirContext;
        this.localSystemTerminologyProvider = localSystemTerminologyProvider;
    }


    private LibraryResolutionProvider<org.hl7.fhir.dstu3.model.Library> getLibraryResourceProvider() {
        return this.libraryResourceProvider;
    }

    private List<Reference> cleanReferences(List<Reference> references) {
        List<Reference> cleanRefs = new ArrayList<>();
        List<Reference> noDupes = new ArrayList<>();

        for (Reference reference : references) {
            boolean dup = false;
            for (Reference ref : noDupes) {
                if (ref.equalsDeep(reference)) {
                    dup = true;
                }
            }
            if (!dup) {
                noDupes.add(reference);
            }
        }
        for (Reference reference : noDupes) {
            cleanRefs.add(new Reference(new IdType(reference.getReferenceElement().getResourceType(),
                    reference.getReferenceElement().getIdPart().replace("#", ""),
                    reference.getReferenceElement().getVersionIdPart())));
        }
        return cleanRefs;
    }

    private Iterable<Reference> getLibraryReferences(DomainResource instance) {
        List<Reference> references = new ArrayList<>();

        if (instance.hasContained()) {
            for (Resource resource : instance.getContained()) {
                if (resource instanceof Library) {
                    resource.setId(resource.getIdElement().getIdPart().replace("#", ""));
                    getLibraryResourceProvider().update((Library) resource);
                    // getLibraryLoader().putLibrary(resource.getIdElement().getIdPart(),
                    // getLibraryLoader().toElmLibrary((Library) resource));
                }
            }
        }

        if (instance instanceof ActivityDefinition) {
            references.addAll(((ActivityDefinition) instance).getLibrary());
        }

        else if (instance instanceof PlanDefinition) {
            references.addAll(((PlanDefinition) instance).getLibrary());
        }

        else if (instance instanceof Measure) {
            references.addAll(((Measure) instance).getLibrary());
        }

        for (Extension extension : instance
                .getExtensionsByUrl("http://hl7.org/fhir/StructureDefinition/cqif-library")) {
            Type value = extension.getValue();

            if (value instanceof Reference) {
                references.add((Reference) value);
            }

            else {
                throw new RuntimeException("Library extension does not have a value of type reference");
            }
        }

        return cleanReferences(references);
    }
    
    /* Evaluates the given CQL expression in the context of the given resource */
    /*
     * If the resource has a library extension, or a library element, that library
     * is loaded into the context for the expression
     */
    /* Evaluates the given CQL expression in the context of the given resource */
    /*
     * If the resource has a library extension, or a library element, that library
     * is loaded into the context for the expression
     */
    public Object evaluateInContext(DomainResource instance, String cql, String patientId) {
        String libraryContent = constructLocalLibrary(instance, cql);
        Map<String, Endpoint> endpointIndex = new HashMap<String, Endpoint>();
        TerminologyProviderFactory terminologyProviderFactory = new DefaultTerminologyProviderFactory<Endpoint>(fhirContext, this.localSystemTerminologyProvider, endpointIndex);
        return evaluateLocalLibrary(instance, libraryContent, terminologyProviderFactory);
    }

    // How should we handle the case that resource is null?
    // We need to know the type to create the library.
    private String constructLocalLibrary(IBaseResource resource, String expression) {
        String resourceType = resource.fhirType();
        String fhirVersion = resource.getStructureFhirVersionEnum().getFhirVersionString();
        fhirVersion = fhirVersion.equals("3.0.2") || fhirVersion.equals("3.0.1")  ? fhirVersion = "3.0.0" : fhirVersion;
        String source = String.format(
                "library LocalLibrary using FHIR version '%s' include FHIRHelpers version '%s' called FHIRHelpers parameter %s %s define Expression: %s",
                fhirVersion, fhirVersion, resourceType, resourceType, expression);

        return source;
    }

    private Object evaluateLocalLibrary(IBaseResource resource, String libraryContent, TerminologyProviderFactory terminologyProviderFactory) {
        org.opencds.cqf.cql.service.Parameters parameters = new org.opencds.cqf.cql.service.Parameters();
        parameters.libraries = Collections.singletonList(libraryContent);
        parameters.expressions = Collections.singletonList(Pair.of("LocalLibrary", "Expression"));
        parameters.parameters = Collections.singletonMap(Pair.of(null, resource.fhirType()), resource);
        DefaultLibraryLoaderFactory libraryFactory = new DefaultLibraryLoaderFactory(this.getLibraryResourceProvider());
        Service service = new Service(libraryFactory, this.dataProviderFactory, terminologyProviderFactory, null, null, null, null);
        Response response = service.evaluate(parameters);

        return response.evaluationResult.forExpression("Expression");
    }

    public Object evaluateInContext(DomainResource instance, String cqlName, String patientId, Boolean aliasedExpression) {
        List<String> libraries = new ArrayList<String>();
        Iterable<Reference> canonicalLibraries = getLibraryReferences(instance);
        Map<String, Endpoint> endpointIndex = new HashMap<String, Endpoint>();
        TerminologyProviderFactory terminologyProviderFactory = new DefaultTerminologyProviderFactory<Endpoint>(fhirContext, this.localSystemTerminologyProvider, endpointIndex);

        if (aliasedExpression) {
            Object result = null;
            for (Reference reference : canonicalLibraries) {
                Library lib =this.libraryResourceProvider.resolveLibraryById(reference.getReferenceElement().getIdPart());
                if (lib == null)
                {
                    throw new RuntimeException("Library with id " + reference.getIdBase() + "not found");
                }
                
                RulerLibraryLoader libraryLoader = LibraryHelper.createLibraryLoader(this.getLibraryResourceProvider());
                // resolve primary library
                org.cqframework.cql.elm.execution.Library library = LibraryHelper.resolveLibraryById(lib.getId(), libraryLoader, this.libraryResourceProvider);
                libraries.add(library.toString());

                //TODO: resolveContextParameters i.e. patient
                DefaultLibraryLoaderFactory libraryFactory = new DefaultLibraryLoaderFactory(this.getLibraryResourceProvider());
                org.opencds.cqf.cql.service.Parameters parameters = new org.opencds.cqf.cql.service.Parameters();
                Map<Pair<String, String>, Object> parametersMap = new HashMap<Pair<String, String>, Object>();
                parameters.libraryName = library.getIdentifier().getId();
                parameters.libraries = libraries;
                parameters.expressions =  new ArrayList<Pair<String, String>>();
                parameters.contextParameters = Collections.singletonMap("Patient", patientId);
                parameters.parameters = parametersMap;
                Service service = new Service(libraryFactory, this.dataProviderFactory, terminologyProviderFactory, null, null, null, null);
                
                Response response = service.evaluate(parameters);

                result = response.evaluationResult.forExpression(cqlName);
                return result;
            }
            throw new RuntimeException("Could not find Expression in Referenced Libraries");
        }
        else {
            return evaluateInContext(instance, cqlName, patientId);
        }
    }

    @Operation(name = "$cql")
    public Bundle evaluate(@OperationParam(name = "code") String code,
            @OperationParam(name = "patientId") String patientId,
            @OperationParam(name="periodStart") String periodStart,
            @OperationParam(name="periodEnd") String periodEnd,
            @OperationParam(name="productLine") String productLine,
			@OperationParam(name = "context") String contextParam,
            @OperationParam(name = "endpoint") Endpoint endpoint,
            @OperationParam(name = "parameters") Parameters parameters) {

        if (patientId == null && contextParam != null && contextParam.equals("Patient")) {
            throw new IllegalArgumentException("Must specify a patientId when executing in Patient context.");
        }

        CqlTranslator translator;
        FhirMeasureBundler bundler = new FhirMeasureBundler();

        RulerLibraryLoader libraryLoader = LibraryHelper.createLibraryLoader(this.getLibraryResourceProvider());

        List<Resource> results = new ArrayList<>();

        try {
            translator = TranslatorHelper.getTranslator(code, libraryLoader.getLibraryManager(),
                    libraryLoader.getModelManager());

            if (translator.getErrors().size() > 0) {
                for (CqlTranslatorException cte : translator.getErrors()) {
                    Parameters result = new Parameters();
                    TrackBack tb = cte.getLocator();
                    if (tb != null) {
                        String location = String.format("[%d:%d]", tb.getStartLine(), tb.getStartChar());
                        result.addParameter().setName("location").setValue(new StringType(location));
                    }

                    result.setId("Error");
                    result.addParameter().setName("error").setValue(new StringType(cte.getMessage()));
                    results.add(result);
                }

                return bundler.bundle(results);
            }
        } catch (IllegalArgumentException e) {
            Parameters result = new Parameters();
            result.setId("Error");
            result.addParameter().setName("error").setValue(new StringType(e.getMessage()));
            results.add(result);
            return bundler.bundle(results);
        }

        org.cqframework.cql.elm.execution.Library library = TranslatorHelper.translateLibrary(translator);

        List<Triple<String, String, String>> usingDefs = UsingHelper.getUsingUrlAndVersion(library.getUsings());

        if (usingDefs.size() > 1) {
            throw new IllegalArgumentException(
                    "Evaluation of Measure using multiple Models is not supported at this time.");
        }

        Map<Pair<String, String>, Object> parametersMap = new HashMap<Pair<String, String>, Object>();

        if (parameters != null)
        {
            for (Parameters.ParametersParameterComponent pc : parameters.getParameter())
            {
                parametersMap.put(Pair.of(null, pc.getName()), pc.getValue());
            }    
        }

        if (periodStart != null && periodEnd != null) {
            // resolve the measurement period
            Interval measurementPeriod = new Interval(DateHelper.resolveRequestDate(periodStart, true), true,
                    DateHelper.resolveRequestDate(periodEnd, false), true);

            parametersMap.put(Pair.of(null, "Measurement Period"),
                    new Interval(DateTime.fromJavaDate((Date) measurementPeriod.getStart()), true,
                            DateTime.fromJavaDate((Date) measurementPeriod.getEnd()), true));
        }
        
        if (productLine != null) {
            parametersMap.put(Pair.of(null, "Product Line"), productLine);
        }

        //TODO: resolveContextParameters i.e. patient
        org.opencds.cqf.cql.service.Parameters evaluationParameters = new org.opencds.cqf.cql.service.Parameters();
        Map<String, Endpoint> endpointIndex = new HashMap<String, Endpoint>();
        if(endpoint != null) {
            endpointIndex.put(endpoint.getAddress(), endpoint);
            evaluationParameters.terminologyUri = endpoint.getAddress();
        }
        evaluationParameters.libraryName = library.getIdentifier().getId();
        evaluationParameters.libraries = Collections.singletonList(library.toString());
        evaluationParameters.parameters = parametersMap;
        evaluationParameters.expressions =  new ArrayList<Pair<String, String>>();
        evaluationParameters.contextParameters = (contextParam.equals("Patient")) ? Collections.singletonMap("Patient", patientId) : Collections.emptyMap();
        DefaultLibraryLoaderFactory libraryFactory = new DefaultLibraryLoaderFactory(this.getLibraryResourceProvider());
        TerminologyProviderFactory terminologyProviderFactory = new DefaultTerminologyProviderFactory<Endpoint>(fhirContext, this.localSystemTerminologyProvider, endpointIndex);

        Parameters result = null;
        try {
            Service service = new Service(libraryFactory, dataProviderFactory, terminologyProviderFactory, null, null, null, null);
            Response response = service.evaluate(evaluationParameters);
                for (Entry<String, Object> expressionEntry : response.evaluationResult.expressionResults.entrySet()) {
                    Object res = expressionEntry.getValue();
                    String executionResults = res.toString();
                    result = getResult(res, executionResults);
                }
        }
        catch (RuntimeException re) {
            re.printStackTrace();

            String message = re.getMessage() != null ? re.getMessage() : re.getClass().getName();
            result.addParameter().setName("error").setValue(new StringType(message));
        }
        results.add(result);

        return bundler.bundle(results);
    }
    
    private Parameters getResult(Object res, String executionResults) {
        Parameters result = new Parameters();
        FhirMeasureBundler bundler = new FhirMeasureBundler();
        if (res == null) {
            result.addParameter().setName("value").setValue(new StringType("null"));
        }
        else if (res instanceof List<?>) {
            if (((List<?>) res).size() > 0 && ((List<?>) res).get(0) instanceof Resource) {
                if (executionResults != null && executionResults.equals("Summary")) {
                    result.addParameter().setName("value").setValue(new StringType(((Resource)((List<?>) res).get(0)).getIdElement().getResourceType() + "/" + ((Resource)((List<?>) res).get(0)).getIdElement().getIdPart()));
                }
                else {
                    result.addParameter().setName("value").setResource(bundler.bundle((Iterable)res));
                }
            }
            else {
                result.addParameter().setName("value").setValue(new StringType(res.toString()));
            }
        }                
        else if (res instanceof Iterable) {
            result.addParameter().setName("value").setResource(bundler.bundle((Iterable)res));
        }
        else if (res instanceof Resource) {
            if (executionResults != null && executionResults.equals("Summary")) {
                result.addParameter().setName("value").setValue(new StringType(((Resource)res).getIdElement().getResourceType() + "/" + ((Resource)res).getIdElement().getIdPart()));
            }
            else {
                result.addParameter().setName("value").setResource((Resource)res);
            }
        }
        else {
            result.addParameter().setName("value").setValue(new StringType(res.toString()));
        }

        result.addParameter().setName("resultType").setValue(new StringType(resolveType(res)));
        return result;
    }

    private String resolveType(Object result) {
        String type = result == null ? "Null" : result.getClass().getSimpleName();
        switch (type) {
            case "BigDecimal":
                return "Decimal";
            case "ArrayList":
                return "List";
            case "FhirBundleCursor":
                return "Retrieve";
        }
        return type;
    }
}
