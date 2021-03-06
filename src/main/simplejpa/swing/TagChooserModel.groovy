/*
 * Copyright 2013 Jocki Hendry.
 *
 * Licensed under the Apache License, Version 2.0 (the 'License');
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an 'AS IS' BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package simplejpa.swing

import groovy.text.SimpleTemplateEngine
import groovy.text.Template
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.swing.DefaultComboBoxModel
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport


class TagChooserModel {

    private static final Logger LOG = LoggerFactory.getLogger(TagChooserModel)

    List values
    List selectedValues
    def templateString
    boolean allowMultiple
    DefaultComboBoxModel comboBoxModel

    def template
    PropertyChangeSupport pcs = new PropertyChangeSupport(this)

    public TagChooserModel() {
        selectedValues = []
        values = []
        templateString = '${value}'
        comboBoxModel = new DefaultComboBoxModel(values.toArray())
        refreshTemplateValues()
        allowMultiple = false
    }

    public void setValues(Collection values) {
        this.values = values
        refreshTemplateValues()
        pcs.firePropertyChange("values", null, values)
    }

    public void replaceValues(Collection values) {
        this.values.clear()
        this.values.addAll(values)
        refreshTemplateValues()
        pcs.firePropertyChange("values", null, values)
    }

    public void setSelectedValues(Collection selectedValues) {
        this.selectedValues = selectedValues
        pcs.firePropertyChange("selectedValues", null, this.selectedValues)
    }

    public List getSelectedValues() {
        List returnValue = []
        returnValue.addAll(selectedValues)
        return returnValue
    }

    public void setAllowMultiple(boolean allowMultiple) {
        boolean oldAllowMultiple = this.allowMultiple
        this.allowMultiple = allowMultiple
        pcs.firePropertyChange("allowMultiple", oldAllowMultiple, this.allowMultiple)
    }

    public void addSelectedValue(def value) {
        selectedValues << value
        pcs.firePropertyChange("selectedValues", null, selectedValues)
    }

    public void replaceSelectedValues(Collection selectedValues) {
        this.selectedValues.clear()
        this.selectedValues.addAll(new ArrayList(selectedValues))
        pcs.firePropertyChange("selectedValues", null, selectedValues)
    }

    public void clearSelectedValues() {
        selectedValues.clear()
        comboBoxModel.selectedItem = null
        pcs.firePropertyChange("selectedValues", null, selectedValues)
    }

    public void addAllValues() {
        selectedValues.clear()
        selectedValues.addAll(values)
        pcs.firePropertyChange("selectedValues", null, selectedValues)
    }

    public void removeSelectedValue(Object value) {
        selectedValues.remove(selectedValues.find { (it!=null) && (it?.toString()==value?.toString()) })
        pcs.firePropertyChange("selectedValues", null, selectedValues)
    }

    public void setTemplateString(def templateString) {
        def oldTemplateString = this.templateString
        this.templateString = templateString
        if (templateString instanceof String) {
            template = new SimpleTemplateEngine().createTemplate(templateString)
        }
        refreshTemplateValues()
        pcs.firePropertyChange("templateString", oldTemplateString, templateString)
    }

    public void refreshTemplateValues() {
        comboBoxModel = new DefaultComboBoxModel(values.toArray())
    }

    String render(def value) {
        if (value==null || value=="This is a prototype display") return ""
        if (templateString instanceof Closure) {
            return templateString.call(value)
        } else {
            if (template) {
                return TemplateRenderer?.make(template,value) ?: value?.toString()
            } else {
                return value?.toString() ?: ""
            }
        }
    }

    public void addPropertyChangeListener(PropertyChangeListener listener) {
        pcs.addPropertyChangeListener(listener)
    }

    public void removePropertyChangeListener(PropertyChangeListener listener) {
        pcs.removePropertyChangeListener(listener)
    }


}
