package de.metas.ui.web.window.datatypes.json;

import org.junit.Assert;
import org.junit.Test;

import de.metas.ui.web.test.util.EnumTestUtils;
import de.metas.ui.web.window.datatypes.json.JSONDocumentLayoutElementField.JSONFieldType;
import de.metas.ui.web.window.descriptor.DocumentLayoutElementFieldDescriptor.FieldType;

/*
 * #%L
 * metasfresh-webui-api
 * %%
 * Copyright (C) 2016 metas GmbH
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

public class JSONFieldTypeTest
{
	@Test
	public void test_fromNullable_fullyCovered()
	{
		EnumTestUtils.assertMappingFullyCovered(FieldType.values(), JSONFieldType::fromNullable);
	}

	@Test
	public void test_fromNullable()
	{
		Assert.assertNull(JSONFieldType.fromNullable((FieldType)null));
		Assert.assertSame(JSONFieldType.ActionButtonStatus, JSONFieldType.fromNullable(FieldType.ActionButtonStatus));
		Assert.assertSame(JSONFieldType.ActionButton, JSONFieldType.fromNullable(FieldType.ActionButton));
	}


}
