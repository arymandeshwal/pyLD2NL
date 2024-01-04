/*-
 * #%L
 * OWL2NL
 * %%
 * Copyright (C) 2015 - 2021 Data and Web Science Research Group (DICE)
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * #L%
 */
package org.aksw.owl2nl.data;

import java.util.Set;

import org.semanticweb.owlapi.model.OWLAxiom;

public class OWL2NLInput extends AInput implements IInput {

  @Override
  public Set<OWLAxiom> getAxioms() {
    if (owlOntology != null) {
      return owlOntology.getAxioms();
    } else {
      LOG.warn("Ontology not set. ");
      return null;
    }
  }
}
