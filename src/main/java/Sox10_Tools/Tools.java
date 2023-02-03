package Sox10_Tools;

import Sox10_Tools.Cellpose.CellposeTaskSettings;
import Sox10_Tools.Cellpose.CellposeSegmentImgPlusAdvanced;
import fiji.util.gui.GenericDialogPlus;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.Plot;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.io.FileSaver;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ij.plugin.Duplicator;
import ij.plugin.RGBStackMerge;
import ij.plugin.RoiEnlarger;
import ij.plugin.RoiScaler;
import ij.plugin.filter.Analyzer;
import ij.process.AutoThresholder;
import java.awt.Color;
import java.awt.Font;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.awt.Rectangle;
import java.util.List;
import javax.swing.ImageIcon;
import loci.common.services.ServiceException;
import loci.formats.FormatException;
import loci.formats.meta.IMetadata;
import loci.plugins.util.ImageProcessorReader;
import mcib3d.geom.Object3D;
import mcib3d.geom.Objects3DPopulation;
import mcib3d.image3d.ImageHandler;
import mcib3d.geom.Point3D;
import mcib3d.geom2.Object3DInt;
import mcib3d.geom2.Objects3DIntPopulation;
import mcib3d.geom2.Objects3DIntPopulationComputation;
import mcib3d.geom2.VoxelInt;
import mcib3d.geom2.measurements.Measure2Distance;
import mcib3d.geom2.measurements.MeasureCentroid;
import mcib3d.geom2.measurements.MeasureIntensity;
import mcib3d.geom2.measurements.MeasureVolume;
import mcib3d.geom2.measurementsPopulation.MeasurePopulationDistance;
import mcib3d.geom2.measurementsPopulation.PairObjects3DInt;
import mcib3d.image3d.ImageFloat;
import mcib3d.image3d.ImageInt;
import mcib3d.image3d.ImageLabeller;
import mcib3d.image3d.distanceMap3d.EDT;
import mcib3d.spatial.descriptors.G_Function;
import mcib3d.spatial.descriptors.SpatialDescriptor;
import mcib3d.spatial.sampler.SpatialModel;
import mcib3d.spatial.sampler.SpatialRandomHardCore;
import mcib3d.utils.ThreadUtil;
import net.haesleinhuepf.clij.clearcl.ClearCLBuffer;
import net.haesleinhuepf.clij2.CLIJ2;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;


/**
 * @author ORION-CIRB
 */
public class Tools {
    
    public final ImageIcon icon = new ImageIcon(this.getClass().getResource("/Orion_icon.png"));
    
    public CLIJ2 clij2 = CLIJ2.getInstance();
    
    private BufferedWriter outPutGlobal;
    private BufferedWriter outPutDetail;
    
    private Calibration cal;
    private double pixelVol;
    String[] chNames = new String[]{"Vessel 1", "Vessel 2", "Sox"};
    
    public String cellposeEnvDir = IJ.isWindows()? System.getProperty("user.home")+File.separator+"miniconda3"+File.separator+"envs"+File.separator+"CellPose" : "/opt/miniconda3/envs/cellpose";
    public String cellposeModel = "cyto";
    public int cellposeDiam = 40;
    public double cellposeStitchTh = 0.5;
    public double minCellVol = 75; // um3
    public double maxCellVol = 750; // um3
    public double minCellInt = 500;
    
    private boolean doF = false;
    private int nbNei = 10; // K-nearest neighbors
    
    public boolean vessel = false;
    private String vesselThMet = "Li";
    public double minVesselVol = 500; // um3
    private double roiDilation = 50; // um
    

    /**
     * Display a message in the ImageJ console and status bar
     */
    public void print(String log) {
        System.out.println(log);
        IJ.showStatus(log);
    }
    
    
    /**
     * Check that needed modules are installed
     */
    public boolean checkInstalledModules() {
        ClassLoader loader = IJ.getClassLoader();
        try {
            loader.loadClass("net.haesleinhuepf.clij2.CLIJ2");
        } catch (ClassNotFoundException e) {
            IJ.log("CLIJ not installed, please install from update site");
            return false;
        }
        try {
            loader.loadClass("mcib3d.geom.Object3D");
        } catch (ClassNotFoundException e) {
            IJ.log("3D ImageJ Suite not installed, please install from update site");
            return false;
        }
        return true;
    }
    
    
    /**
     * Flush and close an image
     */
    public void closeImage(ImagePlus img) {
        img.flush();
        img.close();
    }
    
    
    /**
     * Find images extension
     */
    public String findImageType(File imagesFolder) {
        String ext = "";
        String[] files = imagesFolder.list();
        for (String name : files) {
            String fileExt = FilenameUtils.getExtension(name);
            switch (fileExt) {
               case "nd" :
                   ext = fileExt;
                   break;
                case "czi" :
                   ext = fileExt;
                   break;
                case "lif"  :
                    ext = fileExt;
                    break;
                case "ics" :
                    ext = fileExt;
                    break;
                case "ics2" :
                    ext = fileExt;
                    break;
                case "lsm" :
                    ext = fileExt;
                    break;
                case "tif" :
                    ext = fileExt;
                    break;
                case "tiff" :
                    ext = fileExt;
                    break;
            }
        }
        return(ext);
    }
    
    
    /**
     * Find images in folder
     */
    public ArrayList<String> findImages(String imagesFolder, String imageExt) {
        File inDir = new File(imagesFolder);
        String[] files = inDir.list();
        if (files == null) {
            System.out.println("No image found in " + imagesFolder);
            return null;
        }
        ArrayList<String> images = new ArrayList();
        for (String f : files) {
            // Find images with extension
            String fileExt = FilenameUtils.getExtension(f);
            if (fileExt.equals(imageExt) && !f.startsWith("."))
                images.add(imagesFolder + File.separator + f);
        }
        Collections.sort(images);
        return(images);
    }
       
    
    /**
     * Find image calibration
     */
    public void findImageCalib(IMetadata meta) {
        cal = new Calibration();
        cal.pixelWidth = meta.getPixelsPhysicalSizeX(0).value().doubleValue();
        cal.pixelHeight = cal.pixelWidth;
        if (meta.getPixelsPhysicalSizeZ(0) != null)
            cal.pixelDepth = meta.getPixelsPhysicalSizeZ(0).value().doubleValue();
        else
            cal.pixelDepth = 1;
        cal.setUnit("microns");
        System.out.println("XY calibration = " + cal.pixelWidth + ", Z calibration = " + cal.pixelDepth);
    }
    
    
    /**
     * Find channels name
     * @throws loci.common.services.DependencyException
     * @throws loci.common.services.ServiceException
     * @throws loci.formats.FormatException
     * @throws java.io.IOException
     */
    public String[] findChannels (String imageName, IMetadata meta, ImageProcessorReader reader) throws loci.common.services.DependencyException, ServiceException, FormatException, IOException {
        int chs = reader.getSizeC();
        String[] channels = new String[chs];
        String imageExt =  FilenameUtils.getExtension(imageName);
        switch (imageExt) {
            case "nd" :
                for (int n = 0; n < chs; n++) 
                {
                    if (meta.getChannelID(0, n) == null)
                        channels[n] = Integer.toString(n);
                    else 
                        channels[n] = meta.getChannelName(0, n);
                }
                break;
            case "nd2" :
                for (int n = 0; n < chs; n++) 
                {
                    if (meta.getChannelID(0, n) == null)
                        channels[n] = Integer.toString(n);
                    else 
                        channels[n] = meta.getChannelName(0, n);
                }
                break;
            case "lif" :
                for (int n = 0; n < chs; n++) 
                    if (meta.getChannelID(0, n) == null || meta.getChannelName(0, n) == null)
                        channels[n] = Integer.toString(n);
                    else 
                        channels[n] = meta.getChannelName(0, n);
                break;
            case "czi" :
                for (int n = 0; n < chs; n++) 
                    if (meta.getChannelID(0, n) == null)
                        channels[n] = Integer.toString(n);
                    else 
                        channels[n] = meta.getChannelFluor(0, n);
                break;
            case "ics" :
                for (int n = 0; n < chs; n++) 
                    if (meta.getChannelID(0, n) == null)
                        channels[n] = Integer.toString(n);
                    else 
                        channels[n] = meta.getChannelExcitationWavelength(0, n).value().toString();
                break;
            case "ics2" :
                for (int n = 0; n < chs; n++) 
                    if (meta.getChannelID(0, n) == null)
                        channels[n] = Integer.toString(n);
                    else 
                        channels[n] = meta.getChannelExcitationWavelength(0, n).value().toString();
                break;   
            default :
                for (int n = 0; n < chs; n++)
                    channels[n] = Integer.toString(n);
        }
        return(channels);         
    }
    
    
    /**
     * Generate dialog box
     */
    public int[] dialog(String[] channels) {
        GenericDialogPlus gd = new GenericDialogPlus("Parameters");
        gd.setInsets​(0, 120, 0);
        gd.addImage(icon);
        
        gd.addMessage("Channels", Font.getFont("Monospace"), Color.blue);
        int index = 0;
        for (String chName : chNames) {
            gd.addChoice(chName+" : ", channels, channels[index]);
            index++;
        }
        
        gd.addMessage("Cells detection", Font.getFont("Monospace"), Color.blue);
        gd.addDirectoryField​("Cellpose env directory", cellposeEnvDir);
        gd.addNumericField("Min cell size (µm3) : ", minCellVol, 2);
        gd.addNumericField("Max cell size (µm3) : ", maxCellVol, 2);
        gd.addNumericField("Min cell intensity : ", minCellInt, 2);
        
        gd.addMessage("Cells spatial distribution", Font.getFont("Monospace"), Color.blue);
        gd.addNumericField("Number of neighbors : ", nbNei, 0);
        gd.addCheckbox("Compare with random distribution", false);
        
        gd.addMessage("Cells distance to vessels", Font.getFont("Monospace"), Color.blue);
        gd.addCheckbox("Compute cells distance to closest vessel", false);
        String[] methods = AutoThresholder.getMethods();
        gd.addChoice("Vessels thresholding method :", methods, vesselThMet);
        gd.addNumericField("Min vessel size (µm3) : ", minVesselVol, 2);
        gd.addNumericField("Max cell-vessel distance (µm) : ", roiDilation, 2);
        
        gd.addMessage("Image calibration", Font.getFont("Monospace"), Color.blue);
        gd.addNumericField("XY pixel size (µm) :", cal.pixelWidth, 4);
        gd.addNumericField("Z pixel size (µm) :", cal.pixelDepth, 4);
        gd.showDialog();
        
        int[] chChoices = new int[chNames.length];
        for (int n = 0; n < chChoices.length; n++) {
            chChoices[n] = ArrayUtils.indexOf(channels, gd.getNextChoice());
        }
        
        cellposeEnvDir = gd.getNextString();
        minCellVol = gd.getNextNumber();
        maxCellVol = gd.getNextNumber();
        minCellInt = gd.getNextNumber();
        
        nbNei = (int)gd.getNextNumber();
        doF = gd.getNextBoolean();
        
        vessel = gd.getNextBoolean();
        vesselThMet = gd.getNextChoice();
        minVesselVol = gd.getNextNumber();
        roiDilation = gd.getNextNumber();
        
        cal.pixelWidth = cal.pixelHeight = gd.getNextNumber();
        cal.pixelDepth = gd.getNextNumber();
        pixelVol = cal.pixelWidth * cal.pixelHeight * cal.pixelDepth;
        
        if (gd.wasCanceled())
                chChoices = null;
        
        return(chChoices);
    }
    
    
    /**
     * Scale ROI
     */
    public Roi scaleRoi(Roi roi, int scale) {
        Roi scaledRoi = new RoiScaler().scale(roi, scale, scale, false);
        Rectangle rect = roi.getBounds();
        scaledRoi.setLocation(rect.x*scale, rect.y*scale);
        return scaledRoi;
    }
        
    
    /**
     * Get scaling factor
     */
    public int getPyramidalFactor(ImageProcessorReader reader) {
        reader.setSeries(0);
        int sizeXseries0 = reader.getSizeX();
        reader.setSeries(reader.getSeriesCount()-1);
        int sizeXseriesN = reader.getSizeX();
        return (sizeXseries0 / sizeXseriesN); 
    }
    
    
    /*
     * Look for all 3D cells in a Z-stack: 
     * - apply CellPose in 2D slice by slice 
     * - let CellPose reconstruct cells in 3D using the stitch threshold parameters
     */
    public ImagePlus cellposeDetection(ImagePlus imgIn) throws IOException{
        // Resize image
       double resizeFactor = 1;
       ImagePlus img = imgIn.resize((int)(imgIn.getWidth()*resizeFactor), (int)(imgIn.getHeight()*resizeFactor), 1, "none");
       
       // Define CellPose settings
       CellposeTaskSettings settings = new CellposeTaskSettings(cellposeModel, 1, cellposeDiam, cellposeEnvDir);
       settings.setStitchThreshold(cellposeStitchTh);
       settings.useGpu(true);
       
        // Run CellPose
       CellposeSegmentImgPlusAdvanced cellpose = new CellposeSegmentImgPlusAdvanced(settings, img);
       ImagePlus imgOut = cellpose.run();
       imgOut = imgOut.resize(imgIn.getWidth(), imgIn.getHeight(), "none");
       imgOut.setCalibration(cal);
       
       closeImage(img);
       return imgOut;
    }
    

    /**
     * Return 3D cells population in ROI
     */
    public Objects3DIntPopulation getCellsInRoi(ImagePlus imgLabels, ImagePlus imgRaw, Roi roi) throws IOException{
       ImagePlus img = imgLabels.duplicate();
       img.setRoi(roi);
       IJ.setBackgroundColor(0, 0, 0);
       IJ.run(img, "Clear Outside", "stack");
       img.setCalibration(cal);
       
       // Filter detections
       Objects3DIntPopulation pop = new Objects3DIntPopulation(ImageInt.wrap(img));
       System.out.println(pop.getNbObjects() + " cells detected in ROI");
       Objects3DIntPopulation popFilterSize = new Objects3DIntPopulationComputation(pop).getFilterSize(minCellVol/pixelVol, maxCellVol/pixelVol);
       System.out.println(popFilterSize.getNbObjects() + " cells remaining after size filtering (" + (pop.getNbObjects()-popFilterSize.getNbObjects()) + " filtered out)");
       Objects3DIntPopulation popFilterInt = intensityFilter(popFilterSize, imgRaw, minCellInt); 
       System.out.println(popFilterInt.getNbObjects() + " cells remaining after intensity filtering (" + (popFilterSize.getNbObjects()-popFilterInt.getNbObjects()) + " filtered out)");
       popFilterInt.resetLabels();

       closeImage(img);
       return(popFilterInt);
    }
    
    
    /**
     * Filter objects in population by intensity
     */
    public Objects3DIntPopulation intensityFilter(Objects3DIntPopulation pop, ImagePlus img, double intTh) {
        Objects3DIntPopulation newPop = new Objects3DIntPopulation();
        ImageHandler imh = ImageHandler.wrap(img);
        for (Object3DInt obj : pop.getObjects3DInt()) {
            double meanInt = new MeasureIntensity(obj, imh).getValueMeasurement(MeasureIntensity.INTENSITY_AVG);
            if (meanInt > intTh)
                newPop.addObject(obj);
        }
        return(newPop);
    }
    
    
    /**
     * Detect vessels applying a median filter + DoG + threshold + connected components labeling
     */
    public ImagePlus vesselsDetection(ImagePlus img) {
        ImagePlus imgMed = medianFilter(img, 2, 2);
        ImagePlus imgDOG = DOG(imgMed, 5, 10);
        ImagePlus imgBin = threshold(imgDOG, vesselThMet);
        ImagePlus imgTh = medianFilter(imgBin, 4, 4);
        
        ImageLabeller labeller = new ImageLabeller();
        ImageInt imgLabels = labeller.getLabels(ImageHandler.wrap(imgTh));
        imgLabels.setCalibration(cal);
        
        Objects3DIntPopulation pop = new Objects3DIntPopulation(imgLabels);
        System.out.println(pop.getNbObjects() + " vessels detected");
        Objects3DIntPopulation popFilter = new Objects3DIntPopulationComputation(pop).getFilterSize(minVesselVol/pixelVol, Double.MAX_VALUE);
        System.out.println(popFilter.getNbObjects() + " vessels remaining after size filtering (" + (pop.getNbObjects()-popFilter.getNbObjects()) + " filtered out)");
        popFilter.resetLabels();
        
        ImageHandler imgFilter = ImageHandler.wrap(img).createSameDimensions();
        for(Object3DInt obj: popFilter.getObjects3DInt())
            obj.drawObject(imgFilter, 255);
        
        closeImage(imgMed);
        closeImage(imgDOG);
        closeImage(imgBin);
        closeImage(imgTh);
        closeImage(imgLabels.getImagePlus());
        
        return imgFilter.getImagePlus();
    }
    
       
    /**
     * Median filter using CLIJ2
     */ 
    public ImagePlus medianFilter(ImagePlus img, double sizeXY, double sizeZ) {
       ClearCLBuffer imgCL = clij2.push(img);
       ClearCLBuffer imgCLMed = clij2.create(imgCL);
       clij2.median3DBox(imgCL, imgCLMed, sizeXY, sizeXY, sizeZ);
       clij2.release(imgCL);
       ImagePlus imgMed = clij2.pull(imgCLMed);
       clij2.release(imgCLMed);
       return(imgMed);
    }
        
      
    /**
     * Difference of Gaussians using CLIJ2
     */ 
    public ImagePlus DOG(ImagePlus img, double size1, double size2) {
        ClearCLBuffer imgCL = clij2.push(img);
        ClearCLBuffer imgCLDOG = clij2.create(imgCL);
        clij2.differenceOfGaussian3D(imgCL, imgCLDOG, size1, size1, size1, size2, size2, size2);
        clij2.release(imgCL);
        ImagePlus imgDOG = clij2.pull(imgCLDOG); 
        clij2.release(imgCLDOG);
        return(imgDOG);
    }
        
    
    /**
     * Automatic threshold
     */
    public ImagePlus threshold(ImagePlus img, String thMed) {
        ImagePlus imgBin = img.duplicate();
        imgBin.setSlice((int) Math.round(0.5*imgBin.getNSlices()));
        IJ.setAutoThreshold(imgBin, thMed+" dark");
        IJ.run(imgBin, "Convert to Mask", "method="+thMed+" background=Dark black");
        return(imgBin);
    }
    
    
    /**
     * Compute distance map or inverse distance map
     */
    public ImageFloat localThickness3D(ImagePlus img, boolean inverse) {
        IJ.showStatus("Computing distance map...");
        img.setCalibration(cal);
        ImageFloat edt = new EDT().run(ImageHandler.wrap(img), 0, inverse, ThreadUtil.getNbCpus());
        return(edt);
    }
    
    
    /**
     * Compute vessels skeleton
     */
    public ImagePlus vesselsSkeletonization(ImagePlus imgTh) {
        ImagePlus imgSkel = new Duplicator().run(imgTh);
        IJ.run(imgSkel, "8-bit", "");
        IJ.run(imgSkel, "Invert LUT", "stack");
        IJ.run(imgSkel, "Skeletonize (2D/3D)", "");
        IJ.run(imgSkel, "Invert LUT", "stack");
        return(imgSkel);
    }
    
    
    /**
     * Get vessels in dilated ROI
     */
    public Objects3DIntPopulation getVesselsInRoi(ImagePlus imgTh, Roi roi) {
        ImagePlus img = imgTh.duplicate();
        Roi roiDilated = RoiEnlarger.enlarge(roi, roiDilation/cal.pixelWidth);
        img.setRoi(roiDilated);
        IJ.setBackgroundColor(0, 0, 0);
        IJ.run(img, "Clear Outside", "stack");
        img.setCalibration(cal);        
        
        Objects3DIntPopulation pop = new Objects3DIntPopulation(ImageHandler.wrap(img));
        closeImage(img);
        return pop;
    }
    
    
    /**
     * Get vessels skeleton in dilated  ROI
     */
    public Object3DInt getVesselsSkelInRoi(ImagePlus imgSkel, Roi roi) {
        ImagePlus img = imgSkel.duplicate();
        Roi roiDilated = RoiEnlarger.enlarge(roi, roiDilation/cal.pixelWidth);
        img.setRoi(roiDilated);
        IJ.setBackgroundColor(0, 0, 0);
        IJ.run(img, "Clear Outside", "stack");
        img.setCalibration(cal);
        
        Object3DInt obj = new Objects3DIntPopulation(ImageHandler.wrap(img)).getFirstObject();
        closeImage(img);
        return obj;
    }
    
    
    /**
     * Find cells distance to their nearest vessel with inverse distance map
     */
    public ArrayList<Double> findCellVesselDist(Objects3DIntPopulation cellPop, ImageFloat vesselDistMapInv) {
        ArrayList<Double> cellDist = new ArrayList<>();
        for (Object3DInt cellObj: cellPop.getObjects3DInt()) {
            Point3D pt = new MeasureCentroid​(cellObj).getCentroidAsPoint();
            double dist = vesselDistMapInv.getPixel(pt);
            cellDist.add(dist);	
        }
        return(cellDist);
    }
    
    
    /**
     * Find radius of each cell nearest vessel with distance map
     */
    public ArrayList<Double> findVesselRadius(Objects3DIntPopulation cellPop, ImageFloat vesselDistMap, Object3DInt vesselSkel) {
        ArrayList<Double> vesselRadius = new ArrayList<>();
        for (Object3DInt cellObj: cellPop.getObjects3DInt()) {
            VoxelInt voxelBorder = new Measure2Distance(cellObj, vesselSkel).getBorder2Pix();
            double radius = vesselDistMap.getPixel(voxelBorder);
            vesselRadius.add(radius);
        }
        return(vesselRadius);
    }
    
    
    /**
     * Save results in images
     */
    public void drawResults(Objects3DIntPopulation cellPop, Objects3DIntPopulation vesselPop, ImagePlus imgCell, ImagePlus imgVessel, ArrayList<Double> dists, String pathName) {
        // Cells
        ImageHandler imhCell = ImageHandler.wrap(imgCell).createSameDimensions();
        cellPop.drawInImage(imhCell);
        ImagePlus[] imgColors1 = {imhCell.getImagePlus(), null, null, imgCell};
        ImagePlus imgObjects1 = new RGBStackMerge().mergeHyperstacks(imgColors1, true);
        imgObjects1.setCalibration(cal);
        
        FileSaver imgObjectsFile1 = new FileSaver(imgObjects1);
        imgObjectsFile1.saveAsTiff(pathName+"_cells.tif"); 
        closeImage(imhCell.getImagePlus());
        closeImage(imgObjects1);
        
        // Cells + vessels
        if (vessel) {
            ImageHandler imhCellDist = ImageHandler.wrap(imgCell).createSameDimensions();
            int i = 0;
            for (Object3DInt cell : cellPop.getObjects3DInt()) {
                double dist = dists.get(i);
                cell.drawObject(imhCellDist, (float)dist); 
                i++;
            }
            ImagePlus imgCellDist = imhCellDist.getImagePlus();
            IJ.run(imgCellDist, "Fire", "");
            //IJ.run(imgCellDist,"Calibrate...","function=None unit="+cal.getUnit());
            //IJ.run(imgCellDist, "Calibration Bar...", "location=[Upper Right] fill=White label=Black number=5 decimal=2 font=12 zoom=5 overlay show");
            
            ImageHandler imhVessel = imhCellDist.createSameDimensions();
            vesselPop.drawInImage(imhVessel);
            
            ImagePlus[] imgColors2 = {imgCellDist, null, imhVessel.getImagePlus(), imgVessel};
            ImagePlus imgObjects2 = new RGBStackMerge().mergeHyperstacks(imgColors2, true);
            imgObjects2.setCalibration(cal);
        
            FileSaver imgObjectsFile2 = new FileSaver(imgObjects2);
            imgObjectsFile2.saveAsTiff(pathName+"_vessels.tif"); 
            closeImage(imgCellDist);
            closeImage(imhVessel.getImagePlus());
            closeImage(imgObjects1);
        }
    }
    
    
    /**
     * Write headers in results files
     */
    public void writeHeaders(String outDirResults) throws IOException {
        // Global results
        FileWriter fileGlobal = new FileWriter(outDirResults + "globalResults.xls", false);
        outPutGlobal = new BufferedWriter(fileGlobal);
        outPutGlobal.write("Image name\tROI name\tROI volume\tNb cells\tCells mean intensity\tCells intensity SD\t"
                + "Cells mean volume\tCells volume SD\tCells total volume\tCells mean distance to closest neighbor\t"
                + "Cells distance to closest neighbor SD\tCells mean distance to "+nbNei+" closest neighbors"+"\t"
                + "Cells distance to "+nbNei+" closest neighbors SD"+"\tCells mean of max distance to "+nbNei+" neighbors"+
                "\tCells SD of max distance to "+nbNei+" neighbors\tCells G-function spatial distribution index\tCells mean distance to closest vessel\tVessels mean radius\n");
        outPutGlobal.flush();

        // Detailed results
        FileWriter fileDetail = new FileWriter(outDirResults +"detailedResults.xls", false);
        outPutDetail = new BufferedWriter(fileDetail);
        outPutDetail.write("Image name\tROI name\tCell ID\tCell volume\tCell mean intensity\tCell distance to closest neighbor\tCell mean distance to "+nbNei+" closest neighbors\tCell max distance to "+nbNei+" closest neighbors\tCell distance to closest vessel\tClosest vessel radius\n");
        outPutDetail.flush();
    }
    
    
    /**
     * Compute parameters and save them in results files
     * @throws java.io.IOException
     */
    public void writeResults(Objects3DIntPopulation cellPop, Objects3DIntPopulation vesselPop, ArrayList<Double> dist, ArrayList<Double> radius, ImagePlus imgCell, String roiName, Roi roi,
        String imgName, String outDirResults) throws IOException {
        
        ImageHandler imhCell = ImageHandler.wrap(imgCell);
        MeasurePopulationDistance allDistances = new MeasurePopulationDistance​(cellPop, cellPop, Double.POSITIVE_INFINITY, "DistCenterCenterUnit");
        
        DescriptiveStatistics cellsIntensity = new DescriptiveStatistics();
        DescriptiveStatistics cellsVolume = new DescriptiveStatistics();
        DescriptiveStatistics cellsClosestNeighborDist = new DescriptiveStatistics();
        DescriptiveStatistics cellsNeighborsMeanDist = new DescriptiveStatistics();
        DescriptiveStatistics cellsNeighborsMaxDist = new DescriptiveStatistics();
        
        // Compute and write individual statistics
        print("Computing cells individual parameters...");
        int i = 0;
        for (Object3DInt cellObj: cellPop.getObjects3DInt()) {
            double cellInt = new MeasureIntensity(cellObj, imhCell).getValueMeasurement(MeasureIntensity.INTENSITY_AVG);
            cellsIntensity.addValue(cellInt);
            
            double cellVol = new MeasureVolume(cellObj).getVolumeUnit();
            cellsVolume.addValue(cellVol);
            
            List<PairObjects3DInt> distances = allDistances.getPairsObject1(cellObj.getLabel(), true);
            double closestNeighborDistance = distances.get(1).getPairValue();
            cellsClosestNeighborDist.addValue(closestNeighborDistance);
            
            DescriptiveStatistics distStats = new DescriptiveStatistics();
            for (int d=1; d <= nbNei; d++) {
                distStats.addValue(distances.get(d).getPairValue());
            }
            double closestNeighborsMeanDistance = distStats.getMean();
            cellsNeighborsMeanDist.addValue(closestNeighborsMeanDistance);
            double closestNeighborsMaxDistance = distStats.getMax();
            cellsNeighborsMaxDist.addValue(closestNeighborsMaxDistance);
           
            outPutDetail.write(imgName+"\t"+roiName+"\t"+cellObj.getLabel()+"\t"+cellVol+"\t"+cellInt+"\t"+closestNeighborDistance+"\t"+
                        closestNeighborsMeanDistance+"\t"+closestNeighborsMaxDistance);
            if (vessel)
                outPutDetail.write("\t"+dist.get(i)+"\t"+radius.get(i));
            outPutDetail.write("\n");
            outPutDetail.flush();
            i++;
        }
          
        // Compute and write global statistics
        print("Computing cells global parameters...");
        double roiVol = roiVol(roi, imgCell);
        double cellsIntMean = cellsIntensity.getMean();
        double cellsIntSD = cellsIntensity.getStandardDeviation();
        double cellsVolMean = cellsVolume.getMean(); 
        double cellsVolSD = cellsVolume.getStandardDeviation();
        double cellsVolSum = cellsVolume.getSum(); 
        double cellsClosestNeiDistMean = cellsClosestNeighborDist.getMean(); 
        double cellsClosestNeiDistSD = cellsClosestNeighborDist.getStandardDeviation();
        double cellsNeiMeanDistMean = cellsNeighborsMeanDist.getMean();
        double cellsNeiMeanDistSD = cellsNeighborsMeanDist.getStandardDeviation();
        double cellsNeiMaxDistMean = cellsNeighborsMaxDist.getMean();
        double cellsNeiMaxDistSD = cellsNeighborsMaxDist.getStandardDeviation();
        
        double sdiG = Double.NaN;
        if (doF) {
            System.out.println("Computing G-function-related spatial distribution index...");
            Object3DInt mask = roiMask(imgCell, roi);
            double minDist = Math.pow(3*minCellVol/(4*Math.PI*pixelVol), 1/3) * 2; // min distance = 2 * min cell radius (in pixels)
            String plotName = outDirResults + imgName + "_" + roiName + "_Gplot.tif";
            sdiG = computeSdiG(cellPop, mask, imgCell, minDist, 50, plotName);
        }

        outPutGlobal.write(imgName+"\t"+roiName+"\t"+roiVol+"\t"+cellPop.getNbObjects()+"\t"+cellsIntMean+"\t"+cellsIntSD+"\t"+cellsVolMean+"\t"+
                            cellsVolSD+"\t"+cellsVolSum+"\t"+cellsClosestNeiDistMean+"\t"+cellsClosestNeiDistSD+"\t"+cellsNeiMeanDistMean+"\t"+
                            cellsNeiMeanDistSD+"\t"+cellsNeiMaxDistMean+"\t"+cellsNeiMaxDistSD+"\t"+sdiG);
        if (vessel) {
            double vesselDistMean = dist.stream().mapToDouble(val -> val).average().orElse(0.0);
            double vesselRadiusMean = radius.stream().mapToDouble(val -> val).average().orElse(0.0);
            outPutGlobal.write("\t"+vesselDistMean+"\t"+vesselRadiusMean);
        }
        outPutGlobal.write("\n");
        outPutGlobal.flush();
    }
    
    
    /**
     * Compute ROI volume
     */
    public double roiVol(Roi roi, ImagePlus img) {
        PolygonRoi poly = new PolygonRoi(roi.getFloatPolygon(), Roi.FREEROI);
        poly.setLocation(0, 0);
        img.setRoi(poly);
        
        ResultsTable rt = new ResultsTable();
        Analyzer analyzer = new Analyzer(img, Analyzer.AREA, rt);
        analyzer.measure();
        return(rt.getValue("Area", 0) * img.getNSlices() * cal.pixelDepth);
    }
    
    
    // Get ROI as a 3D object
    public Object3DInt roiMask(ImagePlus img, Roi roi) {
        ImagePlus imgMask = img.duplicate();
        roi.setLocation(0, 0);
        imgMask.setRoi(roi);
        
        for (int n = 1; n <= imgMask.getNSlices(); n++) {
            imgMask.setSlice(n);
            IJ.setForegroundColor(255,255,255);
            IJ.run(imgMask, "Fill", "stack");
            IJ.setBackgroundColor(0, 0, 0);
            IJ.run(imgMask, "Clear Outside", "stack");
        }
        imgMask.setCalibration(cal);
        
        Object3DInt mask = new Object3DInt​(ImageHandler.wrap(imgMask));
        return(mask);
    }
    
    
    /**
     * Compute G-function-related Spatial Distribution Index of cells population in a ROI
     * https://journals.plos.org/ploscompbiol/article?id=10.1371/journal.pcbi.1000853
     */ 
    public Double computeSdiG(Objects3DIntPopulation popInt, Object3DInt roiInt, ImagePlus img, double distHardCore, int numRandomSamples, String plotName) {
        // Convert Object3DInt & Objects3DIntPopulation objects into Object3D & Objects3DPopulation objects
        ImageHandler imhRoi = ImageHandler.wrap(img).createSameDimensions();
        roiInt.drawObject(imhRoi, 1);
        Object3D roi = new Objects3DPopulation(imhRoi).getObject(0);
        ImageHandler imhPop = ImageHandler.wrap(img).createSameDimensions();
        popInt.drawInImage(imhPop);
        Objects3DPopulation pop = new Objects3DPopulation(imhPop);
        
        // Define spatial descriptor and model
        SpatialDescriptor spatialDesc = new G_Function();     
        SpatialModel spatialModel = new SpatialRandomHardCore(pop.getNbObjects(), distHardCore, roi); // average diameter of a cell in pixels
        SpatialStatistics spatialStatistics = new SpatialStatistics(spatialDesc, spatialModel, numRandomSamples, pop); // nb of samples (randomized organizations simulated to compare with the spatial organization of the cells)
        spatialStatistics.setEnvelope(0.05); // 2.5-97.5% envelope error
        spatialStatistics.setVerbose(false);
        Double sdiG = spatialStatistics.getSdi();
        
        Plot plotG = spatialStatistics.getPlot();
        plotG.draw();
        plotG.addLabel(0.1, 0.1, "SDI = " + String.format("%.3f", sdiG));
        ImagePlus imgPlot = plotG.getImagePlus();
        FileSaver plotSave = new FileSaver(imgPlot);
        plotSave.saveAsTiff(plotName);
        closeImage(imgPlot); 
        
        return(sdiG);
    }

    
    /**
     * Close results files
     */
    public void closeResults() throws IOException {
       outPutGlobal.close();
       outPutDetail.close();
    }
     
}