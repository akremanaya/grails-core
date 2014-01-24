package grails.test.runtime;

import grails.async.Promises
import grails.spring.BeanBuilder
import grails.test.MockUtils
import grails.util.Holders
import grails.util.Metadata
import grails.validation.DeferredBindingActions
import grails.web.CamelCaseUrlConverter
import grails.web.UrlConverter
import groovy.transform.CompileStatic

import org.codehaus.groovy.grails.commons.ApplicationAttributes;
import org.codehaus.groovy.grails.commons.ClassPropertyFetcher
import org.codehaus.groovy.grails.commons.CodecArtefactHandler
import org.codehaus.groovy.grails.commons.DefaultGrailsApplication
import org.codehaus.groovy.grails.commons.DefaultGrailsCodecClass
import org.codehaus.groovy.grails.commons.GrailsApplication
import org.codehaus.groovy.grails.commons.spring.GrailsWebApplicationContext
import org.codehaus.groovy.grails.lifecycle.ShutdownOperations
import org.codehaus.groovy.grails.plugins.converters.ConvertersPluginSupport;
import org.codehaus.groovy.grails.validation.ConstraintEvalUtils
import org.codehaus.groovy.grails.web.servlet.GrailsApplicationAttributes
import org.grails.async.factory.SynchronousPromiseFactory
import org.springframework.beans.CachedIntrospectionResults
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.context.ApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.mock.web.MockServletContext
import org.springframework.web.context.WebApplicationContext

@CompileStatic
class GrailsApplicationTestPlugin implements TestPlugin {
    String[] requiredFeatures = ['metaClassCleaner']
    String[] providedFeatures = ['grailsApplication']
    int ordinal = 0

    protected Map validationErrorsMap = new IdentityHashMap()
    protected Set loadedCodecs = []

    void initGrailsApplication(TestRuntime runtime) {
        GrailsWebApplicationContext applicationContext = new GrailsWebApplicationContext()

        DefaultGrailsApplication grailsApplication = new DefaultGrailsApplication()
        grailsApplication.setApplicationContext(applicationContext)
        if(!grailsApplication.metadata[Metadata.APPLICATION_NAME]) {
            grailsApplication.metadata[Metadata.APPLICATION_NAME] = "GrailsUnitTestMixin"
        }
        grailsApplication.applicationContext = applicationContext
        runtime.putValue("grailsApplication", grailsApplication)
        registerBeans(runtime, grailsApplication)
        applicationContext.refresh()

        GrailsWebApplicationContext mainContext = new GrailsWebApplicationContext(applicationContext)
        mainContext.registerSingleton UrlConverter.BEAN_NAME, CamelCaseUrlConverter
        mainContext.refresh()
        grailsApplication.mainContext = mainContext
        
        MockServletContext servletContext = new MockServletContext()
        servletContext.setAttribute WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE, applicationContext
        servletContext.setAttribute ApplicationAttributes.APPLICATION_CONTEXT, applicationContext

        Holders.setServletContext servletContext
        runtime.putValue("servletContext", servletContext)
        
        grailsApplication.initialise()

        applicationInitialized(runtime, grailsApplication)
    }

    void initialState() {
        ExpandoMetaClass.enableGlobally()
        ClassPropertyFetcher.clearClassPropertyFetcherCache()
        CachedIntrospectionResults.clearClassLoader(this.getClass().classLoader)
        Promises.promiseFactory = new SynchronousPromiseFactory()
    }
    
    void registerBeans(TestRuntime runtime, DefaultGrailsApplication grailsApplication) {
        runtime.publishEvent("registerBeans", [grailsApplication: grailsApplication])
    }
    
    void applicationInitialized(TestRuntime runtime, DefaultGrailsApplication grailsApplication) {
        runtime.publishEvent("applicationInitialized", [grailsApplication: grailsApplication])
        // TODO: which is correct mainContext or parentContext?
        ConvertersPluginSupport.enhanceApplication(grailsApplication, grailsApplication.mainContext)
    }
    
    void mockCodec(TestRuntime runtime, Class codecClass) {
        if (loadedCodecs.contains(codecClass)) {
            return
        }
        loadedCodecs << codecClass
        DefaultGrailsCodecClass grailsCodecClass = new DefaultGrailsCodecClass(codecClass)
        grailsCodecClass.configureCodecMethods()
        if(runtime.containsValueFor('grailsApplication') != null) {
            ((GrailsApplication)runtime.getValue('grailsApplication')).addArtefact(CodecArtefactHandler.TYPE, grailsCodecClass)
        }
    }
    
    GrailsApplication getGrailsApplication(TestRuntime runtime) {
        (GrailsApplication)runtime.getValue('grailsApplication')
    }
    
    void mockForConstraintsTests(TestRuntime runtime, Class clazz, List instances) {
        ConstraintEvalUtils.clearDefaultConstraints()
        MockUtils.prepareForConstraintsTests(clazz, validationErrorsMap, instances ?: [], ConstraintEvalUtils.getDefaultConstraints(getGrailsApplication(runtime).config))
    }

    void defineBeans(TestRuntime runtime, Closure callable) {
        def bb = new BeanBuilder()
        def binding = new Binding()
        DefaultGrailsApplication grailsApplication = (DefaultGrailsApplication)runtime.getValue("grailsApplication")
        binding.setVariable "application", grailsApplication
        bb.setBinding binding
        def beans = bb.beans(callable)
        beans.registerBeans((BeanDefinitionRegistry)grailsApplication.getParentContext())
    }

    void resetGrailsApplication(TestRuntime runtime) {
        MockUtils.TEST_INSTANCES.clear()
        ClassPropertyFetcher.clearClassPropertyFetcherCache()
        if(runtime.containsValueFor('grailsApplication')) {
            ((DefaultGrailsApplication)runtime.getValue('grailsApplication'))?.clear()
        }
        //cleanupModifiedMetaClasses()
    }
    
    void shutdownApplicationContext(TestRuntime runtime) {
        if(runtime.containsValueFor("grailsApplication")) {
            DefaultGrailsApplication grailsApplication = (DefaultGrailsApplication)runtime.getValue("grailsApplication")
            ApplicationContext applicationContext = grailsApplication.getParentContext()
            
            if (applicationContext instanceof ConfigurableApplicationContext) {
                if (((ConfigurableApplicationContext) applicationContext).isActive()) {
                    if(grailsApplication.mainContext instanceof Closeable) {
                        ((Closeable)grailsApplication.mainContext).close()
                    }
                    applicationContext.close()
                }
            }
            
            ShutdownOperations.runOperations()
            DeferredBindingActions.clear()
    
            runtime.removeValue("grailsApplication")
            
            Holders.setServletContext null
            runtime.removeValue("servletContext")
            
            Promises.promiseFactory = null
        }
    }
    
    public void onTestEvent(TestEvent event) {
        switch(event.name) {
            case 'beforeClass':
                // trigger grailsApplication initialization by requesting value
                event.runtime.getValue("grailsApplication")
                break
            case 'afterClass':
                shutdownApplicationContext(event.runtime)
                break
            case 'after':
                resetGrailsApplication(event.runtime)
                break
            case 'grailsApplicationRequested':
                initialState()
                initGrailsApplication(event.runtime)
                break
            case 'valueMissing':
                if(event.arguments.name=='grailsApplication') {
                    event.runtime.publishEvent('grailsApplicationRequested', [callerInfo: event.arguments.callerInfo])
                }
                break
            case 'defineBeans':
                defineBeans(event.runtime, (Closure)event.arguments.closure)
                break
            case 'mockCodec':   
                mockCodec(event.runtime, (Class)event.arguments.codecClass)
                break
            case 'mockForConstraintsTests':   
                mockForConstraintsTests(event.runtime, (Class)event.arguments.clazz, (List)event.arguments.instances)
                break
        }
    }
}
