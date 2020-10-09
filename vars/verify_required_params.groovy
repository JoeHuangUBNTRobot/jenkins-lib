def call(name, args, req_params=[], any_params=[])
{
	for (req_param in req_params) {
		if (!args.containsKey(req_param)) {
			error "${name}: Param '${req_param}' is required"
		}
	}
	if (any_params) {
		def found = false
		for (any_param in any_params) {
			if (args.containsKey(any_param)) {
				found = true
				break
			}
		}
		if (!found) {
			error "${name}: At least one of these params is required: ${any_params}"
		}
	}
}
