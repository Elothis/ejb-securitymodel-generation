package ejb.securitymodel.generation;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.handlers.HandlerUtil;



/**
 * Handler getting called by clicking the added menu entry on a Java project to generate the security model from.
 * 
 * @author Fabian Glittenberg
 * 
 */
public class GenerateHandler extends AbstractHandler{

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		System.out.println("Model generation");
		//getting selected item
		ISelection selection = HandlerUtil.getActiveWorkbenchWindow(event).getActivePage().getSelection();
		//casting it to IStructuredSelection to retrieve first element
		if(selection != null && selection instanceof IStructuredSelection){
			IStructuredSelection strucSelection = (IStructuredSelection) selection;
			Object projectObj = strucSelection.getFirstElement();
			
			//double checking type safety before attaining IJavaProject
			if(projectObj instanceof IJavaProject){
				
				try {
					IJavaProject project = (IJavaProject)projectObj;
					//Generating the security model for the selected project
					System.out.println("Generator called with project " + project.getElementName());
					
					ModelGenerator generator = new ModelGenerator();
					generator.generateModel(project);					
					
				} catch (JavaModelException e) {
					e.printStackTrace();
				}
			}
		}
		return null;
	}

}
