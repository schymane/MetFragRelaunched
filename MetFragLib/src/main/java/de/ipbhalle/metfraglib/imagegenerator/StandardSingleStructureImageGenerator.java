package de.ipbhalle.metfraglib.imagegenerator;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;
import java.awt.image.RenderedImage;
import java.awt.image.BufferedImage;
import java.io.IOException;

import javax.imageio.ImageIO;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openscience.cdk.exception.CDKException;
import org.openscience.cdk.exception.InvalidSmilesException;
import org.openscience.cdk.interfaces.IAtomContainer;
import org.openscience.cdk.renderer.AtomContainerRenderer;
import org.openscience.cdk.renderer.RendererModel;
import org.openscience.cdk.renderer.SymbolVisibility;
import org.openscience.cdk.renderer.color.CDK2DAtomColors;
import org.openscience.cdk.renderer.font.AWTFontManager;
import org.openscience.cdk.renderer.generators.BasicSceneGenerator;
import org.openscience.cdk.renderer.generators.IGenerator;
import org.openscience.cdk.renderer.generators.standard.StandardGenerator;
import org.openscience.cdk.renderer.visitor.AWTDrawVisitor;
import org.openscience.cdk.tools.manipulator.AtomContainerManipulator;
import org.openscience.cdk.layout.StructureDiagramGenerator;

import de.ipbhalle.metfraglib.additionals.MoleculeFunctions;
import de.ipbhalle.metfraglib.interfaces.ICandidate;
import de.ipbhalle.metfraglib.interfaces.IFragment;
import de.ipbhalle.metfraglib.interfaces.IImageGenerator;
import de.ipbhalle.metfraglib.interfaces.IMolecularStructure;
import de.ipbhalle.metfraglib.parameter.Constants;

public class StandardSingleStructureImageGenerator implements IImageGenerator {

    protected static final Logger logger = LogManager.getLogger();

	protected double strokeRatio = Constants.DEFAULT_STRUCTURE_STROKE_RATIO;
	protected int imageWidth = Constants.DEFAULT_STRUCTURE_IMAGE_WIDTH;
	protected int imageHeight = Constants.DEFAULT_STRUCTURE_IMAGE_HEIGHT;
	protected AtomContainerRenderer renderer;
	protected Color backgroundColor = new Color(1.0f, 1.0f, 1.0f, 0.0f);

	public StandardSingleStructureImageGenerator(Font font) {
		java.util.List<IGenerator<IAtomContainer>> generators = new java.util.ArrayList<IGenerator<IAtomContainer>>();
		generators.add(new BasicSceneGenerator());
		generators.add(new StandardGenerator(font));
		this.renderer = new AtomContainerRenderer(generators, new AWTFontManager());
	}
	
	public StandardSingleStructureImageGenerator() {
		java.util.List<IGenerator<IAtomContainer>> generators = new java.util.ArrayList<IGenerator<IAtomContainer>>();
		generators.add(new BasicSceneGenerator());
		generators.add(new StandardGenerator(new Font("Verdana", Font.PLAIN, 18)));
		this.renderer = new AtomContainerRenderer(generators, new AWTFontManager());
	}

	/**
	 * draw image and return it as RenderedImage
	 * 
	 * @param structure
	 * @return
	 * @throws Exception
	 */
	public RenderedImage generateImage(final IMolecularStructure structure) throws Exception {
		return this.generateImage(structure.getStructureAsIAtomContainer());
	}

	/**
	 * draw image and return it as RenderedImage
	 * 
	 * @param structure
	 * @return
	 * @throws CDKException
	 */
	public RenderedImage generateImage(IMolecularStructure precursorMolecule, final IFragment structure) throws Exception {
		return this.generateImage(structure.getStructureAsIAtomContainer(precursorMolecule));
	}

	/**
	 * draw image and return it as RenderedImage
	 * 
	 * @param structure
	 * @return
	 * @throws CDKException
	 */
	public RenderedImage generateImage(final IAtomContainer structure) {
		Image image = new BufferedImage(this.imageWidth, this.imageHeight, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g2 = (Graphics2D) image.getGraphics();
		g2.setColor(this.backgroundColor);
		g2.fillRect(0, 0, this.imageWidth, this.imageHeight);
		try {
			IAtomContainer moleculeToDraw = AtomContainerManipulator.removeHydrogens(structure);

			Rectangle drawArea = new Rectangle(this.imageWidth, this.imageHeight);

			AtomContainerManipulator.percieveAtomTypesAndConfigureAtoms(moleculeToDraw);
			StructureDiagramGenerator sdg = new StructureDiagramGenerator();
            sdg.setMolecule(moleculeToDraw);
            sdg.generateCoordinates();
			this.renderer.setup(sdg.getMolecule(), drawArea);
			RendererModel rendererModel = this.renderer.getRenderer2DModel();
			rendererModel.set(StandardGenerator.Visibility.class, SymbolVisibility.iupacRecommendations());
    		rendererModel.set(StandardGenerator.AtomColor.class, new CDK2DAtomColors());
    		rendererModel.set(StandardGenerator.StrokeRatio.class, this.strokeRatio);
			Rectangle2D bounds = new Rectangle2D.Double(0, 0, this.imageWidth, this.imageHeight);
			
			this.renderer.paint(sdg.getMolecule(), new AWTDrawVisitor(g2), bounds, true);

		} catch (Exception e) {
			return (RenderedImage) image;
		}
		return (RenderedImage) image;
	}

	public RenderedImage generateImage(final IAtomContainer structure, String id) {
		Rectangle drawArea = new Rectangle(this.imageWidth, this.imageHeight);
		Image image = new BufferedImage(this.imageWidth, this.imageHeight, BufferedImage.TYPE_INT_ARGB);
		try {
			IAtomContainer moleculeToDraw = AtomContainerManipulator.removeHydrogens(structure);
			AtomContainerManipulator.percieveAtomTypesAndConfigureAtoms(moleculeToDraw);
			StructureDiagramGenerator sdg = new StructureDiagramGenerator();
            sdg.setMolecule(moleculeToDraw);
            sdg.generateCoordinates();
            this.renderer.setup(sdg.getMolecule(), drawArea);
			
			RendererModel rendererModel = this.renderer.getRenderer2DModel();
			rendererModel.set(StandardGenerator.Visibility.class, SymbolVisibility.iupacRecommendations());
    		rendererModel.set(StandardGenerator.AtomColor.class, new CDK2DAtomColors());
    		rendererModel.set(StandardGenerator.StrokeRatio.class, this.strokeRatio);
			Rectangle2D bounds = new Rectangle2D.Double(0, 0, this.imageWidth, this.imageHeight);
			
			Graphics2D g2 = (Graphics2D) image.getGraphics();
			g2.setColor(new Color(1.0f, 1.0f, 1.0f, 0.0f));
			g2.fillRect(0, 0, this.imageWidth, this.imageHeight);
			this.renderer.paint(sdg.getMolecule(), new AWTDrawVisitor(g2), bounds, true);
		} catch (Exception e) {
			System.err.println("could not draw structure " + id);
			return (RenderedImage) image;
		}
		return (RenderedImage) image;
	}

	public int getImageWidth() {
		return imageWidth;
	}

	public void setImageWidth(int imageWidth) {
		this.imageWidth = imageWidth;
	}

	public int getImageHeight() {
		return imageHeight;
	}

	public void setImageHeight(int imageHeight) {
		this.imageHeight = imageHeight;
	}

	public void nullify() {
		this.renderer = null;
	}

	public double getStrokeRatio() {
		return strokeRatio;
	}

	public void setStrokeRation(double strokeRatio) {
		this.strokeRatio = strokeRatio;
	}

	public RenderedImage generateImage(ICandidate candidate) {
		RenderedImage image = null;
		try {
			image = this.generateImage(candidate.getAtomContainer(), candidate.getIdentifier());
		} catch (Exception e) {
			e.printStackTrace();
			return (RenderedImage) this.getDefaultImage();
		}
		return image;
	}

	protected Image getDefaultImage() {
		Image image = new BufferedImage(this.imageWidth, this.imageHeight, BufferedImage.TYPE_INT_ARGB);

		Graphics2D g2 = (Graphics2D) image.getGraphics();
		g2.setColor(this.backgroundColor);
		g2.fillRect(0, 0, this.imageWidth, this.imageHeight);
		return image;
	}
	
	public void setBackgroundColor(Color color) {
		this.backgroundColor = color;
	}
	
	public static void main(String[] args) throws InvalidSmilesException, IOException {
		 IAtomContainer m = null;
			try {
				//m = MoleculeFunctions.getAtomContainerFromInChI("InChI=1S/C15H14O6/c16-8-4-11(18)9-6-13(20)15(21-14(9)5-8)7-1-2-10(17)12(19)3-7/h1-5,13,15-20H,6H2/t13-,15-/m1/s1");
				m = MoleculeFunctions.getAtomContainerFromSMILES("C1C(OC2=C(C1=O)C(=C(C=C2)O)O)C3=CC=CC=C3O");

				MoleculeFunctions.prepareAtomContainer(m, false);
				//MoleculeFunctions.removeHydrogens(m);
			} catch (Exception e) {
				e.printStackTrace();
			}
	    StandardSingleStructureImageGenerator s = new StandardSingleStructureImageGenerator();
	    s.setImageHeight(1500);
	    s.setImageWidth(1500);
	    s.setStrokeRation(2.2);
	    RenderedImage img = s.generateImage(m, "1");
	    
	    ImageIO.write((RenderedImage) img, "PNG", new java.io.File("/tmp/file.png"));
	}
}
