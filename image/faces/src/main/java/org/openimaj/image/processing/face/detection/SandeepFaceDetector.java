/**
 * Copyright (c) 2011, The University of Southampton and the individual contributors.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 *   * 	Redistributions of source code must retain the above copyright notice,
 * 	this list of conditions and the following disclaimer.
 *
 *   *	Redistributions in binary form must reproduce the above copyright notice,
 * 	this list of conditions and the following disclaimer in the documentation
 * 	and/or other materials provided with the distribution.
 *
 *   *	Neither the name of the University of Southampton nor the names of its
 * 	contributors may be used to endorse or promote products derived from this
 * 	software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.openimaj.image.processing.face.detection;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;

import org.openimaj.image.DisplayUtilities;
import org.openimaj.image.FImage;
import org.openimaj.image.ImageUtilities;
import org.openimaj.image.MBFImage;
import org.openimaj.image.colour.Transforms;
import org.openimaj.image.connectedcomponent.ConnectedComponentLabeler;
import org.openimaj.image.model.pixel.HistogramPixelModel;
import org.openimaj.image.model.pixel.MBFPixelClassificationModel;
import org.openimaj.image.pixel.ConnectedComponent;
import org.openimaj.image.pixel.ConnectedComponent.ConnectMode;
import org.openimaj.image.processing.convolution.FSobelMagnitude;
import org.openimaj.image.processor.PixelProcessor;
import org.openimaj.image.processor.connectedcomponent.render.OrientatedBoundingBoxRenderer;
import org.openimaj.math.geometry.shape.Rectangle;


/**
 * Implementation of a face detector along the lines of 
 * "Human Face Detection in Cluttered Color Images Using Skin Color and Edge Information"
 * K. Sandeep and A. N. Rajagopalan (IIT/Madras)
 * 
 * @author Jonathon Hare <jsh2@ecs.soton.ac.uk>
 *
 */
public class SandeepFaceDetector implements FaceDetector<CCDetectedFace, MBFImage> {
	/**
	 * The golden ratio (for comparing facial height/width)
	 */
	public final static double GOLDEN_RATIO = 1.618033989; // ((1 + sqrt(5) / 2) 

	private final String DEFAULT_MODEL = "/org/openimaj/image/processing/face/detection/skin-histogram-16-6.bin";

	private ConnectedComponentLabeler ccl;

	MBFPixelClassificationModel skinModel;
	float skinThreshold = 0.1F;
	float edgeThreshold = 125F/255F;
	float goldenRatioThreshold = 0.65F;
	float percentageThreshold = 0.55F;

	public SandeepFaceDetector() {
		ccl = new ConnectedComponentLabeler(ConnectMode.CONNECT_8);

		try {
			if (this.getClass().getResource(DEFAULT_MODEL) == null) {
				//This is to create the skin model
				skinModel = new HistogramPixelModel(16, 6);
				MBFImage rgb = ImageUtilities.readMBF(this.getClass().getResourceAsStream("skin.png"));
				skinModel.learnModel(Transforms.RGB_TO_HS(rgb));
				//ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(new File("D:\\Programming\\skin-histogram-16-6.bin")));
				//oos.writeObject(skinModel);
			} else {
				// Load in the skin model
				ObjectInputStream ois = new ObjectInputStream(this.getClass().getResourceAsStream(DEFAULT_MODEL));
				skinModel = (MBFPixelClassificationModel) ois.readObject();
			}
		} catch (Exception e) {
			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}

	public SandeepFaceDetector(MBFPixelClassificationModel skinModel) {
		ccl = new ConnectedComponentLabeler(ConnectMode.CONNECT_8);
		this.skinModel = skinModel;
	}

	protected FImage generateSkinColorMap(MBFImage inputHS) {
		FImage map = skinModel.predict(inputHS);

		map.clipMin(skinThreshold);
		return map;
	}

	protected FImage generateSobelMagnitudes(MBFImage inputRGB) {
		MBFImage mag = inputRGB.process(new FSobelMagnitude());
		FImage ret = mag.flattenMax().clipMax(edgeThreshold);
		DisplayUtilities.display(ret);
		return ret;
	}

	protected FImage generateFaceMap(FImage skin, FImage edge) {
		skin.processInline(new PixelProcessor<Float>() {
			@Override
			public Float processPixel(Float pixel, Number[]... otherpixels) {
				float edge = (Float)(otherpixels[0][0]);

				if (edge != 0 && pixel != 0) return 1F;
				return 0F;
			}
		}, edge);

		return skin;
	}

	protected List<CCDetectedFace> extractFaces(FImage faceMap, FImage skinMap, FImage image) {
		List<ConnectedComponent> blobs = ccl.findComponents(faceMap);
		List<CCDetectedFace> faces = new ArrayList<CCDetectedFace>();

		for (ConnectedComponent blob : blobs) {
			if (blob.calculateArea() > 1000) {
				double [] centroid = blob.calculateCentroid();
				double [] hw = blob.calculateAverageHeightWidth(centroid);

				double percentageSkin = calculatePercentageSkin(skinMap, 
						(int)Math.round(centroid[0] - (hw[0]/2)), 
						(int)Math.round(centroid[1] - (hw[1]/2)),
						(int)Math.round(centroid[0] + (hw[0]/2)),
						(int)Math.round(centroid[1] + (hw[1]/2)));

				double ratio = hw[0] / hw[1];

				if (Math.abs(ratio - GOLDEN_RATIO) < goldenRatioThreshold && percentageSkin > percentageThreshold) {
					Rectangle r = blob.calculateRegularBoundingBox();
					faces.add(new CCDetectedFace(r, image.extractROI(r), blob));
				}
			}
		}

		return faces;
	}

	private double calculatePercentageSkin(FImage skinMap, int l, int t, int r, int b) {
		int npix = 0;
		int nskin = 0;

		l = Math.max(l, 0);
		t = Math.max(t, 0);
		r = Math.min(r, skinMap.getWidth());
		b = Math.min(b, skinMap.getHeight());

		for (int y=t; y<b; y++) {
			for (int x=l; x<r; x++) {
				npix++;
				if (skinMap.pixels[y][x] != 0)
					nskin++;
			}
		}

		return (double)nskin / (double)npix;
	}

	@Override
	public List<CCDetectedFace> detectFaces(MBFImage inputRGB) {
		FImage skin = generateSkinColorMap(Transforms.RGB_TO_HS(inputRGB));
		FImage edge = generateSobelMagnitudes(inputRGB);

		FImage map = generateFaceMap(skin, edge);

		return extractFaces(map, skin, Transforms.calculateIntensityNTSC(inputRGB));
	}

	public ConnectedComponentLabeler getCcl() {
		return ccl;
	}

	public void setCcl(ConnectedComponentLabeler ccl) {
		this.ccl = ccl;
	}

	public MBFPixelClassificationModel getSkinModel() {
		return skinModel;
	}

	public void setSkinModel(MBFPixelClassificationModel skinModel) {
		this.skinModel = skinModel;
	}

	public float getSkinThreshold() {
		return skinThreshold;
	}

	public void setSkinThreshold(float skinThreshold) {
		this.skinThreshold = skinThreshold;
	}

	public float getEdgeThreshold() {
		return edgeThreshold;
	}

	public void setEdgeThreshold(float edgeThreshold) {
		this.edgeThreshold = edgeThreshold;
	}

	public float getGoldenRatioThreshold() {
		return goldenRatioThreshold;
	}

	public void setGoldenRatioThreshold(float goldenRatioThreshold) {
		this.goldenRatioThreshold = goldenRatioThreshold;
	}

	public float getPercentageThreshold() {
		return percentageThreshold;
	}

	public void setPercentageThreshold(float percentageThreshold) {
		this.percentageThreshold = percentageThreshold;
	}

	//run the face detector following the conventions of the ocv detector
	public static void main(String [] args) throws IOException {
		if (args.length < 1 || args.length > 2) {
			System.err.println("Usage: SandeepFaceDetector filename [filename_out]");
			return;
		}

		String inputImage = args[0];
		String outputImage = null;
		if (args.length == 2) outputImage = args[1];


		SandeepFaceDetector sfd = new SandeepFaceDetector();

		//tweek the default settings
		sfd.edgeThreshold = 0.39F;
		sfd.ccl = new ConnectedComponentLabeler(ConnectMode.CONNECT_4);

		MBFImage image = ImageUtilities.readMBF(new File(inputImage));
		List<CCDetectedFace> faces = sfd.detectFaces(image);

		if (outputImage != null) {
			OrientatedBoundingBoxRenderer<Float> render = new OrientatedBoundingBoxRenderer<Float>(image.getWidth(), image.getHeight(), 1.0F);
			for (CCDetectedFace f : faces) f.connectedComponent.process(render);
			image.multiplyInline(render.getImage().inverse());

			ImageUtilities.write(image, outputImage.substring(outputImage.lastIndexOf('.')+1), new File(outputImage));
		}

		for (CCDetectedFace f : faces) {
			System.out.format("%s, %d, %d, %d, %d\n", 
					"uk.ac.soton.ecs.jsh2.image.proc.tools.face.detection.skin-histogram-16-6.bin",
					f.bounds.x,
					f.bounds.y,
					f.bounds.width,
					f.bounds.height
			);
		}
	}

	@Override
	public Class<CCDetectedFace> getDetectedFaceClass() {
		return CCDetectedFace.class;
	}

	@Override
	public void readBinary(DataInput in) throws IOException {
		//ccl;

		try {
			byte [] bytes = new byte[in.readInt()];
			in.readFully(bytes);
			skinModel = (MBFPixelClassificationModel) new ObjectInputStream(new ByteArrayInputStream(bytes)).readObject();
		} catch (ClassNotFoundException e) {
			throw new IOException(e);
		}
		
		skinThreshold = in.readFloat();
		edgeThreshold = in.readFloat();
		goldenRatioThreshold = in.readFloat();
		percentageThreshold = in.readFloat();
	}

	@Override
	public byte[] binaryHeader() {
		return "SdFD".getBytes();
	}

	@Override
	public void writeBinary(DataOutput out) throws IOException {
		//ccl;

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		ObjectOutputStream oos = new ObjectOutputStream(baos);
		oos.writeObject(skinModel);
		oos.close();
		
		out.writeInt(baos.size());
		out.write(baos.toByteArray());
		
		out.writeFloat(skinThreshold);
		out.writeFloat(edgeThreshold);
		out.writeFloat(goldenRatioThreshold);
		out.writeFloat(percentageThreshold);
	}
}
