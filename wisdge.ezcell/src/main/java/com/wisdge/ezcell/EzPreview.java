package com.wisdge.ezcell;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class EzPreview {
	private int totalRows;
	private int totalCols;
	private List<Object> elements;
}
