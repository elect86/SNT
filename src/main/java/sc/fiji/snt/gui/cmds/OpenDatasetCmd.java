/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2023 Fiji developers.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

package sc.fiji.snt.gui.cmds;

import java.io.File;

import net.imagej.ImageJ;
import sc.fiji.snt.gui.GuiUtils;

import org.scijava.command.Command;
import org.scijava.plugin.Plugin;

import ij.IJ;
import ij.ImagePlus;

/**
 * Implements the 'Choose Tracing Image (From File)...' command.
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, initializer = "init",
	label = "Change Tracing Image")
public class OpenDatasetCmd extends CommonDynamicCmd implements Command {

//	@Parameter
//	private DatasetIOService ioService;
//
//	@Parameter
//	private ConvertService convertService;

	// Not a @Parameter so that we can use SNT's file chooser (remembering last accessed directory, etc.)
	private File file;

	@Override
	public void run() {
		init(true);
		try {
			// In theory we should be able to use ioService but the
			// following seems to always generate a virtual stack
//			final Dataset ds = ioService.open(file.getAbsolutePath());
//			final ImagePlus imp = convertService.convert(ds, ImagePlus.class);
//			snt.initialize(imp);
			file = new GuiUtils(ui).getImageFile(file);
			if (file != null) {
				ImagePlus imp = IJ.openImage(file.getAbsolutePath());
				imp = comvertInPlaceToCompositeAsNeeded(imp);
				if (imp.getType() != ImagePlus.COLOR_RGB) {
					snt.initialize(imp);
				}
			}
		}
		catch (final NullPointerException ex) {
			error("Loading of image failed (see Console for details)... "
					+ "File may not be valid or may encode a proprietary format "
					+ "not immediately recognized. Please open the image using "
					+ "IJ/Bioformats (if not yet open). Then, load it in SNT using "
					+ "'Choose Tracing Image -> From Open Image...'.");
			ex.printStackTrace();
		} finally {
			resetUI();
		}
	}

	public static void main(final String... args) {
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		ij.command().run(OpenDatasetCmd.class, true);
	}

}
