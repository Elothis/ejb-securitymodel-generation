package ejb.securitymodel.generation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jdt.core.IAnnotation;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMemberValuePair;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;

import de.mkonersmann.ejb31.Ejb31Factory;
import de.mkonersmann.ejb31.EnterpriseBean;
import de.mkonersmann.ejb31.EnterpriseBeanOperationSecurity;
import de.mkonersmann.ejb31.EnterpriseBeanSecurity;
import de.mkonersmann.ejb31.MessageDrivenBean;
import de.mkonersmann.ejb31.MessageDrivenBeanOperation;
import de.mkonersmann.ejb31.OperationSignature;
import de.mkonersmann.ejb31.Role;
import de.mkonersmann.ejb31.SessionBean;
import de.mkonersmann.ejb31.SessionBeanOperation;

/**
 * Responsible for generating an EJB-security model instance for a given Java project.
 * 
 * @author Fabian Glittenberg
 *
 */
public class ModelGenerator {
	
	private Ejb31Factory factory;
	private Resource resource;
	private List<Role> createdRoles;
	
	public String URI_PATH;
	
	/**
	 * ModelGenerator constructor.
	 * @param project
	 */
	public ModelGenerator(){
		this.factory = Ejb31Factory.eINSTANCE;
		this.createdRoles = new ArrayList<>();
	}
	
	
	/**
	 * Creates a security model for the specified Java project.
	 * @param project Java project
	 * @param outputPath path within workspace to save the created model instance to
	 * @throws JavaModelException
	 */
	public void generateModel(IJavaProject project, String outputPath) throws JavaModelException{
			//preparations to save the model
			StringBuilder sb = new StringBuilder();
			sb.append("file:///").append(System.getProperty("user.home").replace('\\', '/'))
			.append('/').append(outputPath).append('/')
			.append(project.getElementName()).append("/SecurityModel.xmi");
			
			URI_PATH = sb.toString();

	        ResourceSet resSet = new ResourceSetImpl();
	        this.resource = resSet.createResource(URI.createURI(URI_PATH));
			
			List<ICompilationUnit> beans = getJavaBeans(project);
			buildModelInstance(beans);
			
			System.out.println("Model successfully created: " + URI_PATH);
	}
	
	
	/**
	 * Sets the model data for a given list of Java Beans.
	 * @param beans
	 * @throws JavaModelException
	 */
	private void buildModelInstance(List<ICompilationUnit> beans) throws JavaModelException{
		
		for(ICompilationUnit unit : beans){
			
			//bean to add
			EnterpriseBean bean;
			JavaBeanType beanType;
			boolean asyncBean = false;
			
			switch(checkJavaBeanType(unit)){
			case STATELESS:
				bean = factory.createStatelessSessionBean();
				beanType = JavaBeanType.STATELESS;
				break;
			case STATEFUL:
				bean = factory.createStatefulSessionBean();
				beanType = JavaBeanType.STATEFUL;
				break;
			case SINGLETON:
				bean = factory.createSingletonSessionBean();
				beanType = JavaBeanType.SINGLETON;
				break;
			case MESSAGEDRIVEN:
				bean = factory.createMessageDrivenBean();
				beanType = JavaBeanType.MESSAGEDRIVEN;
				break;
			default:
				System.out.println("This should not get printed, because all compilation units should be java beans at this point");
				return;
			}
			
			//initialize bean fields
			bean.setName(unit.getElementName().split(".java")[0]);	//set name without ~.java at end
			
			//set bean-level security
			EnterpriseBeanSecurity beanSecuritySpecs = createEnterpriseBeanSecuritySpecs(unit);
			bean.setSecuritySpecs(beanSecuritySpecs);
			
			//check at class level if all bean methods will be asynchronous
			asyncBean = isAsyncBean(unit);
			
			//setting bean methods
			IType beanClass = unit.getAllTypes()[0];
			//getting all methods declared in the bean class
			IMethod[] methods = beanClass.getMethods();
			for(IMethod method : methods){
				if(beanType == JavaBeanType.MESSAGEDRIVEN){		//if it's a message driven bean, create MessageDrivenBeanOperation and add it
					((MessageDrivenBean)bean).getOwnedOperations().add(createMessageDrivenBeanOperation(method));
				}
				else{											//if it's a session bean, create SessionBeanOperation and add it
					((SessionBean)bean).getOwnedOperations().add(createSessionBeanOperation(method, asyncBean));
				}
			}
			
			//adding created bean to resource object, which gets persisted after all required objects for the model are created
			resource.getContents().add(bean);
			
		} //end for-loop over all beans
		
		//persisting the created content
		try {
			resource.save(Collections.EMPTY_MAP);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	/** Creates an operation object for a given method of a session bean.
	 * @param method
	 * @param asyncBean true if class level Asynchronous annotation applied
	 * @return session bean operation object
	 */
	private SessionBeanOperation createSessionBeanOperation(IMethod method, boolean asyncBean){
		boolean asyncMethod = false;
		
		//determine if its an asynchronous method
		if( asyncBean || method.getAnnotation("Asynchronous").exists() || method.getAnnotation("javax.ejb.Asynchronous").exists()){
			asyncMethod = true;
		}
		
		//create operation-object accordingly
		SessionBeanOperation op = asyncMethod ? factory.createAsynchronousOperation() : factory.createSynchronousOperation();
		
		//setting signature and security specs
		op.setSignature(createOperationSignature(method));
		op.setSecuritySpecs(createEnterpriseBeanOperationSecurity(method));
		
		return op;
	}
	
	/** 
	 * Creates an operation object for a given method of a message driven bean.
	 * @param method
	 * @return message driven bean operation object
	 */
	private MessageDrivenBeanOperation createMessageDrivenBeanOperation(IMethod method){
		MessageDrivenBeanOperation op = factory.createMessageDrivenBeanOperation();
		
		//setting signature and security specs
		op.setSignature(createOperationSignature(method));
		op.setSecuritySpecs(createEnterpriseBeanOperationSecurity(method));
		return op;
	}
	
	/**
	 * Creates the method-level security spec-object for a given EJB method.
	 * @param method
	 * @return bean operation security spec-object
	 */
	private EnterpriseBeanOperationSecurity createEnterpriseBeanOperationSecurity(IMethod method){
		EnterpriseBeanOperationSecurity secOp = factory.createEnterpriseBeanOperationSecurity();
		
		//check method for @PermitAll and @DenyAll
		if(method.getAnnotation("PermitAll").exists() || method.getAnnotation("javax.annotation.security.PermitAll").exists()){
			secOp.setPermitAll(true);
		}
		if(method.getAnnotation("DenyAll").exists() || method.getAnnotation("javax.annotation.security.DenyAll").exists()){
			secOp.setDenyAll(true);
		}
		
		//check method for @RolesAllowed
		if(method.getAnnotation("RolesAllowed").exists() || method.getAnnotation("javax.annotation.security.RolesAllowed").exists()){
			Object[] annotationValues;
			//get the allowed roles from the annotation values
			if(method.getAnnotation("RolesAllowed").exists()){
				annotationValues = getAnnotationValues(method.getAnnotation("RolesAllowed"));
			}
			else {
				annotationValues = getAnnotationValues(method.getAnnotation("javax.annotation.security.RolesAllowed"));
			}
			
			if(annotationValues != null){
				//add Role objects
				for(Object role : annotationValues){
					Role allowedRole = factory.createRole();
					allowedRole.setName((String)role);
					//check if role already exists
					if(createdRoles.isEmpty()){
						//if no role is created yet, add this one
						secOp.getRolesAllowed().add(allowedRole);
						createdRoles.add(allowedRole);
						resource.getContents().add(allowedRole);
					}
					else{
						//check created roles for this particular role
						//use already existing role if present, create new if not
						boolean roleFound = false;
						for(Role existingRole : createdRoles){
							if(EcoreUtil.equals(existingRole, allowedRole)){
								secOp.getRolesAllowed().add(existingRole);
								roleFound = true;
							}
						}
						if(!roleFound){
							secOp.getRolesAllowed().add(allowedRole);
							createdRoles.add(allowedRole);
							resource.getContents().add(allowedRole);
						}
					}
				}
			}
		}
		
		return secOp;
	}
	
	/** Creates the operation signature for a given method.
	 * @param method
	 * @return operation signature of the given method
	 */
	private OperationSignature createOperationSignature(IMethod method){
		OperationSignature sig = factory.createOperationSignature();
		sig.setName(method.getElementName());
		resource.getContents().add(sig);
		return sig;
	}
	
	/**
	 * Creates the bean-level security spec-object for a given Java Bean class file.
	 * @param unit Java Bean class file
	 * @return security spec-object containing respective information
	 */
	private EnterpriseBeanSecurity createEnterpriseBeanSecuritySpecs(ICompilationUnit unit){
		EnterpriseBeanSecurity beanSecuritySpecs = factory.createEnterpriseBeanSecurity();
		
		try {
			IType beanType = unit.getTypes()[0];
			
			//check class for @PermitAll and @DenyAll
			if(beanType.getAnnotation("PermitAll").exists() || beanType.getAnnotation("javax.annotation.security.PermitAll").exists()){
				beanSecuritySpecs.setPermitAll(true);
			}
			if(beanType.getAnnotation("DenyAll").exists() || beanType.getAnnotation("javax.annotation.security.DenyAll").exists()){
				beanSecuritySpecs.setDenyAll(true);
			}
			
			//check class for @DeclareRoles
			if(beanType.getAnnotation("DeclareRoles").exists() || beanType.getAnnotation("javax.annotation.security.DeclareRoles").exists()){
				Object[] annotationValues;
				//get the declared roles from the annotation values
				if(beanType.getAnnotation("DeclareRoles").exists()){
					annotationValues = getAnnotationValues(beanType.getAnnotation("DeclareRoles"));
				}
				else {
					annotationValues = getAnnotationValues(beanType.getAnnotation("javax.annotation.security.DeclareRoles"));
				}
				
				if(annotationValues != null){
					//add Role objects
					for(Object role : annotationValues){
						Role declaredRole = factory.createRole();
						declaredRole.setName((String)role);
						//check if role already exists
						if(createdRoles.isEmpty()){
							//if no role is created yet, add this one
							beanSecuritySpecs.getRolesDeclared().add(declaredRole);
							createdRoles.add(declaredRole);
							resource.getContents().add(declaredRole);
						}
						else{
							//check created roles for this particular role
							//use already existing role if present, create new if not
							boolean roleFound = false;
							for(Role existingRole : createdRoles){
								if(EcoreUtil.equals(existingRole, declaredRole)){
									beanSecuritySpecs.getRolesDeclared().add(existingRole);
									roleFound = true;
								}
							}
							if(!roleFound){
								beanSecuritySpecs.getRolesDeclared().add(declaredRole);
								createdRoles.add(declaredRole);
								resource.getContents().add(declaredRole);
							}
						}
					}
				}
			}
			
			//check class for @RolesAllowed
			if(beanType.getAnnotation("RolesAllowed").exists() || beanType.getAnnotation("javax.annotation.security.RolesAllowed").exists()){
				Object[] annotationValues;
				//get the allowed roles from the annotation values
				if(beanType.getAnnotation("RolesAllowed").exists()){
					annotationValues = getAnnotationValues(beanType.getAnnotation("RolesAllowed"));
				}
				else {
					annotationValues = getAnnotationValues(beanType.getAnnotation("javax.annotation.security.RolesAllowed"));
				}
				
				if(annotationValues != null){
					//add Role objects
					for(Object role : annotationValues){
						Role allowedRole = factory.createRole();
						allowedRole.setName((String)role);
						//check if role already exists
						if(createdRoles.isEmpty()){
							//if no role is created yet, add this one
							beanSecuritySpecs.getRolesAllowed().add(allowedRole);
							createdRoles.add(allowedRole);
							resource.getContents().add(allowedRole);
						}
						else{
							//check created roles for this particular role
							//use already existing role if present, create new if not
							boolean roleFound = false;
							for(Role existingRole : createdRoles){
								if(EcoreUtil.equals(existingRole, allowedRole)){
									beanSecuritySpecs.getRolesAllowed().add(existingRole);
									roleFound = true;
								}
							}
							if(!roleFound){
								beanSecuritySpecs.getRolesAllowed().add(allowedRole);
								createdRoles.add(allowedRole);
								resource.getContents().add(allowedRole);
							}
						}
					}
				}
			}
			
			//check class for @RunAs
			if(beanType.getAnnotation("RunAs").exists() || beanType.getAnnotation("javax.annotation.security.RunAs").exists()){
				Object[] annotationValues;
				//get the declared roles from the annotation values
				if(beanType.getAnnotation("RunAs").exists()){
					annotationValues = getAnnotationValues(beanType.getAnnotation("RunAs"));
				}
				else {
					annotationValues = getAnnotationValues(beanType.getAnnotation("javax.annotation.security.RunAs"));
				}
				
				if(annotationValues != null){
					//add Role object
					Role runAsRole = factory.createRole();
					runAsRole.setName((String)annotationValues[0]);
					
					if(createdRoles.isEmpty()){
						//if no role is created yet, add this one
						beanSecuritySpecs.setRunAs(runAsRole);
						createdRoles.add(runAsRole);
						resource.getContents().add(runAsRole);
					}
					else{
						//check created roles for this particular role
						//use already existing role if present, create new if not
						boolean roleFound = false;
						for(Role existingRole : createdRoles){
							if(EcoreUtil.equals(existingRole, runAsRole)){
								beanSecuritySpecs.setRunAs(existingRole);
								roleFound = true;
							}
						}
						if(!roleFound){
							beanSecuritySpecs.setRunAs(runAsRole);
							createdRoles.add(runAsRole);
							resource.getContents().add(runAsRole);
						}
					}
				}
			}
			
		} catch (JavaModelException e) {
			e.printStackTrace();
		}
		
		return beanSecuritySpecs;
	}
	
	/**
	 * Checks if all methods are asynchronous for a given Bean class file.
	 * @param unit bean class file
	 * @return true if Asynchronous-annotation is applied to bean, false otherwise
	 */
	private boolean isAsyncBean(ICompilationUnit unit){
		try {
			IType beanType = unit.getTypes()[0];
			
			//check class for @Asnychronous
			if(beanType.getAnnotation("Asynchronous").exists() || beanType.getAnnotation("javax.ejb.Asynchronous").exists()){
				return true;
			}
		} catch (JavaModelException e) {
			e.printStackTrace();
		}
		return false;
	}

	/**
	 * Identifies all Java Beans within the respective Java project.
	 * @return List of beans
	 * @throws JavaModelException
	 */
	private List<ICompilationUnit> getJavaBeans(IJavaProject project) {
		List<ICompilationUnit> beans = new ArrayList<>();
		
		try {
			//get all packages in the project
			IPackageFragment[] packages = project.getPackageFragments();
			
			//find all classes in these packages that represent Java Beans
			for(IPackageFragment p : packages){
				//check if it contains source files
				if(p.getKind() == IPackageFragmentRoot.K_SOURCE){
					//go through all class-files
					for(ICompilationUnit unit : p.getCompilationUnits()){
						if(checkJavaBeanType(unit) != JavaBeanType.NONE){
							//if java bean found, add to list
							beans.add(unit);
						}
					}
				}
			}
		} catch (JavaModelException e) {
			e.printStackTrace();
		}
		
		return beans;
	}
	
	/**
	 * Checks if the given class is a Java Bean
	 * @param unit
	 * @return true if class is a Java Bean, false otherwise
	 * @throws JavaModelException 
	 */
	private JavaBeanType checkJavaBeanType(ICompilationUnit unit){

		try {
			//get the first defined class in compilation unit
			// --> ignoring possible additional classes defined in the same .java
			IType classType = unit.getTypes()[0];
			
			//check if class is a stateless session bean
			if(classType.getAnnotation("Stateless").exists() || classType.getAnnotation("javax.ejb.Stateless").exists()){
				return JavaBeanType.STATELESS;
			}
			//check if class is a stateful session bean
			else if(classType.getAnnotation("Stateful").exists() || classType.getAnnotation("javax.ejb.Stateful").exists()){
				return JavaBeanType.STATEFUL;
			}
			//check if class is a singleton bean
			else if(classType.getAnnotation("Singleton").exists() || classType.getAnnotation("javax.ejb.Singleton").exists()){
				return JavaBeanType.SINGLETON;
			}
			//check if class is a message driven bean
			else if(classType.getAnnotation("MessageDriven").exists() || classType.getAnnotation("javax.ejb.MessageDriven").exists()){
				return JavaBeanType.MESSAGEDRIVEN;
			}
		} catch (JavaModelException e) {
			e.printStackTrace();
		}
		
		//if none of these annotations are found, the class is no Java Bean
		return JavaBeanType.NONE;
	}
	
	
	/**
	 * Returns an array of the specified annotation values.
	 * Only one element if the annotation has just one value, multiple elements otherwise.
	 * @param annotation
	 * @return array of annotation values
	 */
	private Object[] getAnnotationValues(IAnnotation annotation){
		Object[] result;
		
		try {
	    	IMemberValuePair[] valuePairs = annotation.getMemberValuePairs();
	        for (IMemberValuePair valuePair : valuePairs) {
	            if ("value".equals(valuePair.getMemberName())) {
	            	if(valuePair.getValue() instanceof Object[]){	//multiple annotation values via Array declaration
		        		result = (Object[])valuePair.getValue();
		        		return result;
		        	}
	            	else{											//only one annotation value via single String declaration
	            		result = new String[1];
	            		result[0] = valuePair.getValue();
	            		return result;
	            	}
	            }
	        }
	    } catch (JavaModelException ex) {
	    	//this can get triggered if the annotation doesnt exist, so just ignore it then and return null
	    	return null;
	    }
		
		return null;
	}

}
