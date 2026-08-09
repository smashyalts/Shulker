use kube::{
    Api, ResourceExt,
    api::{Patch, PatchParams},
    core::object::HasStatus,
};
use serde::{Serialize, de::DeserializeOwned};

use crate::reconcilers::Result;

use super::BuilderReconcilerError;

pub async fn patch_status<
    Resource: kube::Resource<DynamicType = ()> + DeserializeOwned + HasStatus,
>(
    api: &Api<Resource>,
    pp: &PatchParams,
    resource: &Resource,
) -> Result<()>
where
    Resource::Status: Serialize,
{
    if let Some(status) = resource.status() {
        let status_json = serde_json::json!({
            "apiVersion": Resource::api_version(&()),
            "kind": Resource::kind(&()),
            "status": status
        });

        api.patch_status(&resource.name_any(), pp, &Patch::Apply(&status_json))
            .await
            .map_err(BuilderReconcilerError::FailedToUpdateStatus)?;
    }

    Ok(())
}
