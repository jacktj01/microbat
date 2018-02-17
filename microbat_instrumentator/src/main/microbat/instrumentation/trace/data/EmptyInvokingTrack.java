package microbat.instrumentation.trace.data;

import microbat.model.value.VarValue;

public class EmptyInvokingTrack extends InvokingDetail {

	public EmptyInvokingTrack() {
		super();
	}

	@Override
	public boolean updateRelevant(String parentVarId, String fieldVarId) {
		return false;
	}

	@Override
	public VarValue getReturnValue() {
		return null;
	}

	@Override
	public void setReturnValue(VarValue returnValue) {
		// do nothing
	}

	@Override
	public void addWrittenValue(VarValue value) {
		// do nothing
	}

	@Override
	public String getInvokeNodeId() {
		return null;
	}

	
}