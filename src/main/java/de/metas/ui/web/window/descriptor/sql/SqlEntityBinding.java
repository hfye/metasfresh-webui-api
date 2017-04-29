package de.metas.ui.web.window.descriptor.sql;

import org.adempiere.ad.expression.api.IStringExpression;

/*
 * #%L
 * metasfresh-webui-api
 * %%
 * Copyright (C) 2017 metas GmbH
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

public interface SqlEntityBinding
{
	String getTableName();

	String getTableAlias();

	/** @return field binding or throws exception in case it was not found */
	SqlEntityFieldBinding getFieldByFieldName(String fieldName);
	
	default IStringExpression getFieldOrderBy(String fieldName)
	{
		return getFieldByFieldName(fieldName).getSqlOrderBy();
	}

}