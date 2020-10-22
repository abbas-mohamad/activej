/*
 * Copyright (C) 2020 ActiveJ LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.activej.common.exception.parse;

public class TruncatedDataException extends ParseException {
	public TruncatedDataException() {
		super();
	}

	public TruncatedDataException(String message) {
		super(null, message);
	}

	public TruncatedDataException(Class<?> component) {
		super(component, "Data has ended unexpectedly");
	}

	public TruncatedDataException(Class<?> component, String message) {
		super(component, message);
	}
}
