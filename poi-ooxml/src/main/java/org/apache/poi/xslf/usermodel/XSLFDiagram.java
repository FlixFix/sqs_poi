/* ====================================================================
   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
==================================================================== */

package org.apache.poi.xslf.usermodel;

import com.microsoft.schemas.office.drawing.x2008.diagram.CTGroupShape;
import com.microsoft.schemas.office.drawing.x2008.diagram.CTShape;
import org.apache.poi.ooxml.POIXMLDocumentPart;
import org.apache.poi.util.Beta;
import org.apache.xmlbeans.XmlObject;
import org.openxmlformats.schemas.drawingml.x2006.diagram.CTRelIds;
import org.openxmlformats.schemas.drawingml.x2006.main.CTGraphicalObjectData;
import org.openxmlformats.schemas.drawingml.x2006.main.CTGroupShapeProperties;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTextParagraph;
import org.openxmlformats.schemas.presentationml.x2006.main.CTApplicationNonVisualDrawingProps;
import org.openxmlformats.schemas.presentationml.x2006.main.CTGraphicalObjectFrame;
import org.openxmlformats.schemas.presentationml.x2006.main.CTGroupShapeNonVisual;
import org.openxmlformats.schemas.presentationml.x2006.main.CTShapeNonVisual;

import javax.xml.namespace.QName;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Representation of a DrawingML Diagram
 * <p>
 * This class converts the diagram to an {@link XSLFGroupShape} accessible via {@link #getGroupShape()}. The underlying
 * {@link XSLFDiagramDrawing} used to create the group shape is accessible via {@link #getDiagramDrawing()}.
 * <p>
 * In pptx files, these diagrams are generated by creating SmartArt. When a pptx has SmartArt, a directory with the
 * following structure is created:
 *
 * <pre>
 * ppt/
 *   diagrams/
 *     data#.xml
 *     drawing#.xml^
 *     colors#.xml
 *     quickStyle#.xml
 *     layout#.xml
 *     rels/
 *       data#.xml.rels
 *       drawing#.xml.rels
 * </pre>
 * <p>
 * ^The `drawing#.xml` file is not in the OpenXML spec. It was added as an extension by Microsoft, namespace:
 * http://schemas.microsoft.com/office/drawing/2008/diagram
 * <p>
 * The drawing#.xml file contains the rendered output of the diagram. This class reads the underlying drawing#.xml and
 * converts it to a {@link XSLFGroupShape}.
 * <p>
 * The data, drawing, colors, and quickStyle files are in the OpenXML spec. These contain the instructions that define
 * how to render the diagram. Rendering diagrams from these files is not trivial, they support for loops, if/elses, etc.
 * Integrating such a change into POI would be quite sophisticated and challenging.
 *
 * @since POI 5.2.3
 */
@Beta
public class XSLFDiagram extends XSLFGraphicFrame {

    public static final String DRAWINGML_DIAGRAM_URI = "http://schemas.openxmlformats.org/drawingml/2006/diagram";
    private final XSLFDiagramDrawing _drawing;
    private final XSLFDiagramGroupShape _groupShape;

    /* package protected */ XSLFDiagram(CTGraphicalObjectFrame shape, XSLFSheet sheet) {
        super(shape, sheet);
        _drawing = readDiagramDrawing(shape, sheet);
        _groupShape = initGroupShape();
    }

    private static boolean hasTextContent(CTShape msShapeCt) {
        if (msShapeCt.getTxBody() == null || msShapeCt.getTxXfrm() == null) {
            return false;
        }
        // A shape has text content when there is at least 1 paragraph with 1 paragraph run list
        List<CTTextParagraph> paragraphs = msShapeCt.getTxBody().getPList();
        return paragraphs.stream()
                .flatMap(p -> p.getRList().stream())
                .anyMatch(run -> run.getT() != null && !run.getT().trim().isEmpty());
    }

    private static XSLFDiagramDrawing readDiagramDrawing(CTGraphicalObjectFrame shape, XSLFSheet sheet) {
        CTGraphicalObjectData graphicData = shape.getGraphic().getGraphicData();
        XmlObject[] children = graphicData.selectChildren(new QName(DRAWINGML_DIAGRAM_URI, "relIds"));

        if (children.length == 0) {
            return null;
        }

        // CTRelIds doesn't contain a relationship to the drawing#.xml
        // But it has the same name as the other data#.xml, layout#.xml, etc.
        CTRelIds relIds = (CTRelIds) children[0];
        POIXMLDocumentPart dataModelPart = sheet.getRelationById(relIds.getDm());
        if (dataModelPart == null) {
            return null;
        }
        String dataPartName = dataModelPart.getPackagePart().getPartName().getName();
        String drawingPartName = dataPartName.replace("data", "drawing");
        for (POIXMLDocumentPart.RelationPart rp : sheet.getRelationParts()) {
            if (drawingPartName.equals(rp.getDocumentPart().getPackagePart().getPartName().getName())) {
                if (rp.getDocumentPart() instanceof XSLFDiagramDrawing) {
                    return rp.getDocumentPart();
                }
            }
        }
        return null;
    }

    /**
     * Returns the underlying {@link XSLFDiagramDrawing} used to create this diagram.
     * <p>
     * NOTE: Modifying this drawing will not update the groupShape returned from {@link #getGroupShape()}.
     */
    public XSLFDiagramDrawing getDiagramDrawing() {
        return _drawing;
    }

    /**
     * Returns the diagram represented as a grouped shape.
     */
    public XSLFDiagramGroupShape getGroupShape() {
        return _groupShape;
    }

    // If the shape has text, two XSLFShapes are created. One shape element and one textbox element.
    private List<org.openxmlformats.schemas.presentationml.x2006.main.CTShape> convertShape(CTShape msShapeCt) {
        org.openxmlformats.schemas.presentationml.x2006.main.CTShape shapeCt
                = org.openxmlformats.schemas.presentationml.x2006.main.CTShape.Factory.newInstance();

        // The fields on MS CTShape largely re-use the underlying openxml classes.
        // We just copy the fields from the MS CTShape to the openxml CTShape
        shapeCt.setStyle(msShapeCt.getStyle());
        shapeCt.setSpPr(msShapeCt.getSpPr());

        CTShapeNonVisual nonVisualCt = shapeCt.addNewNvSpPr();
        nonVisualCt.setCNvPr(msShapeCt.getNvSpPr().getCNvPr());
        nonVisualCt.setCNvSpPr(msShapeCt.getNvSpPr().getCNvSpPr());
        nonVisualCt.setNvPr(CTApplicationNonVisualDrawingProps.Factory.newInstance());
        shapeCt.setNvSpPr(nonVisualCt);

        ArrayList<org.openxmlformats.schemas.presentationml.x2006.main.CTShape> shapes = new ArrayList<>();
        shapes.add(shapeCt);

        if (hasTextContent(msShapeCt)) {
            org.openxmlformats.schemas.presentationml.x2006.main.CTShape textShapeCT = convertText(msShapeCt, nonVisualCt);
            shapes.add(textShapeCT);
        }

        return shapes;
    }

    private org.openxmlformats.schemas.presentationml.x2006.main.CTShape convertText(CTShape msShapeCt, CTShapeNonVisual nonVisualCt) {
        org.openxmlformats.schemas.presentationml.x2006.main.CTShape textShapeCT
                = org.openxmlformats.schemas.presentationml.x2006.main.CTShape.Factory.newInstance();

        // SmartArt shapes define a separate `txXfrm` property for the placement of text inside the shape
        // We can't easily (is it even possible?) set a separate xfrm for the text on the openxml CTShape.
        // Instead, we create a separate textbox shape with the same xfrm.
        org.openxmlformats.schemas.drawingml.x2006.main.CTShapeProperties textShapeProps = textShapeCT.addNewSpPr();
        textShapeProps.setXfrm(msShapeCt.getTxXfrm());

        textShapeCT.setTxBody(msShapeCt.getTxBody());
        textShapeCT.setStyle(msShapeCt.getStyle());
        // Create a copy of the nonVisualCt when setting it for the text box.
        // If we shared the one object, a consumer may be surprised that updating the text shape properties
        // also updates the parent shape.
        textShapeCT.setNvSpPr((CTShapeNonVisual) nonVisualCt.copy());

        return textShapeCT;
    }

    private XSLFDiagramGroupShape initGroupShape() {
        XSLFDiagramDrawing drawing = getDiagramDrawing();
        if (drawing == null || drawing.getDrawingDocument() == null) {
            return null;
        }

        CTGroupShape msGroupShapeCt = drawing.getDrawingDocument().getDrawing().getSpTree();
        if (msGroupShapeCt == null || msGroupShapeCt.getSpList().isEmpty()) {
            return null;
        }
        return convertMsGroupToGroupShape(msGroupShapeCt, drawing);
    }

    private XSLFDiagramGroupShape convertMsGroupToGroupShape(CTGroupShape msGroupShapeCt, XSLFDiagramDrawing drawing) {
        org.openxmlformats.schemas.presentationml.x2006.main.CTGroupShape groupShapeCt
                = org.openxmlformats.schemas.presentationml.x2006.main.CTGroupShape.Factory.newInstance();

        CTGroupShapeProperties groupShapePropsCt = groupShapeCt.addNewGrpSpPr();

        CTGroupShapeNonVisual groupShapeNonVisualCt = groupShapeCt.addNewNvGrpSpPr();
        groupShapeNonVisualCt.setCNvPr(msGroupShapeCt.getNvGrpSpPr().getCNvPr());
        groupShapeNonVisualCt.setCNvGrpSpPr(msGroupShapeCt.getNvGrpSpPr().getCNvGrpSpPr());
        groupShapeNonVisualCt.setNvPr(CTApplicationNonVisualDrawingProps.Factory.newInstance());

        for (CTShape msShapeCt : msGroupShapeCt.getSpList()) {
            List<org.openxmlformats.schemas.presentationml.x2006.main.CTShape> shapes = convertShape(msShapeCt);
            groupShapeCt.getSpList().addAll(shapes);
        }

        Rectangle2D anchor = super.getAnchor();
        Rectangle2D interiorAnchor = new Rectangle2D.Double(0, 0, anchor.getWidth(), anchor.getHeight());

        XSLFDiagramGroupShape groupShape = new XSLFDiagramGroupShape(groupShapeCt, getSheet(), drawing);
        groupShape.setAnchor(anchor);
        groupShape.setInteriorAnchor(interiorAnchor);
        groupShape.setRotation(super.getRotation());
        return groupShape;
    }

    /**
     * Simple wrapper around XSLFGroupShape to enable accessing underlying diagram relations correctly.
     * <p>
     * Diagrams store relationships to media in `drawing#.xml.rels`. These relationships are accessible using
     * {@link #getRelationById(String)}.
     */
    static class XSLFDiagramGroupShape extends XSLFGroupShape {

        private XSLFDiagramDrawing diagramDrawing;

        protected XSLFDiagramGroupShape(org.openxmlformats.schemas.presentationml.x2006.main.CTGroupShape shape,
                                        XSLFSheet sheet) {
            super(shape, sheet);
        }

        private XSLFDiagramGroupShape(org.openxmlformats.schemas.presentationml.x2006.main.CTGroupShape shape,
                                      XSLFSheet sheet,
                                      XSLFDiagramDrawing diagramDrawing) {
            super(shape, sheet);
            this.diagramDrawing = diagramDrawing;
        }

        POIXMLDocumentPart getRelationById(String id) {
            return diagramDrawing.getRelationById(id);
        }
    }
}
